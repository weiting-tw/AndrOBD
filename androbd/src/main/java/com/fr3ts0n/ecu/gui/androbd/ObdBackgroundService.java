/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fr3ts0n.androbd.plugin.mqtt.MqttPublisher;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvChangeListener;
import com.fr3ts0n.pvs.ProcessVar;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one and only owner of the OBD connection.
 * <p>
 * Connect → ELM reset/init → ECU detect → OBD service 01 (live data) polling →
 * MQTT (Home Assistant). Everything runs here in a foreground service, independent
 * of any UI. Previously the UI (MainActivity) and this service each held their own
 * connection and only the "OBD data" page started PID polling / plugin feeding,
 * which is why data (e.g. mileage) only reached Home Assistant after opening that page.
 */
public class ObdBackgroundService extends Service
{
	private static final String TAG = "ObdBackgroundService";
	private static final Logger log = Logger.getLogger(TAG);

	/** Intent actions */
	public static final String ACTION_START = "com.fr3ts0n.androbd.action.START";
	/** (Re-)connect immediately, e.g. after a new device was selected */
	public static final String ACTION_RECONNECT = "com.fr3ts0n.androbd.action.RECONNECT";
	/** Disconnect and stop the service (no auto restart until started again) */
	public static final String ACTION_STOP = "com.fr3ts0n.androbd.action.STOP";

	/** Preference keys */
	public static final String PREF_DEVICE_ADDRESS = "LAST_DEV_ADDRESS";
	public static final String PREF_DEVICE_NAME = "LAST_DEV_NAME";
	public static final String PREF_ECU_ADDRESS = "LAST_ECU_ADDRESS";
	/** false after the user pressed "stop" — then boot does not auto-start */
	public static final String PREF_SERVICE_ENABLED = "service_enabled";
	public static final String PREF_KEEP_AWAKE = "keep_cpu_awake";
	public static final String PREF_COMM_MEDIUM = "comm_medium";
	public static final String PREF_BT_SECURE = "bt_secure_connection";
	public static final String PREF_NET_ADDRESS = "device_address";
	public static final String PREF_NET_PORT = "device_port";
	public static final String PREF_PROTOCOL = "protocol";
	public static final String PREF_ADAPTIVE_TIMING = "adaptive_timing_mode";
	public static final String PREF_CUSTOM_INIT = "elm_custom_init_cmds";
	public static final String PREF_MEASURE_SYSTEM = "measure_system";
	/* ELM settings of the former full app — no longer editable, but still applied */
	public static final String PREF_ELM_CMD_DISABLE = "elm_cmd_disable";
	public static final String PREF_ELM_RESET_ON_NRC = "elm_reset_on_nrc";
	public static final String PREF_ELM_MIN_TIMEOUT = "elm_min_timeout";
	public static final String PREF_DATA_DISABLE_MAX = "data_disable_max";

	private static final String CHANNEL_ID = "obd_service_channel";
	private static final int NOTIFICATION_ID = 1001;

	/** Retry delays (ms) while the adapter is unreachable; last value repeats forever */
	private static final long[] RETRY_DELAYS = {3000, 5000, 10000, 20000, 30000};
	/** A connect attempt that did not finish within this time is aborted */
	private static final long CONNECT_TIMEOUT = 30000;
	/** Connected, but no OBD data for this long → drop the link and reconnect */
	private static final long DATA_TIMEOUT = 60000;
	private static final long WATCHDOG_PERIOD = 15000;
	/** Data older than this is not published (prevents re-sending stale values) */
	private static final long DATA_FRESHNESS = 120000;

	/* ---------- state visible to the UI (read-only, polled) ---------- */
	private static volatile boolean sRunning = false;
	private static volatile CommService.STATE sConnState = CommService.STATE.NONE;
	private static volatile ElmProt.STAT sElmStatus = ElmProt.STAT.UNDEFINED;
	private static volatile String sMessage = "";
	private static volatile long sLastDataMs = 0L;
	private static volatile String sDeviceName = null;

	/** Recent events (newest last) shown on the status screen for troubleshooting */
	private static final java.util.LinkedList<String> sEvents = new java.util.LinkedList<>();
	private static final int MAX_EVENTS = 40;

	private static final String PREF_EVENT_LOG = "event_log";
	/** app context to persist the event log (survives process restarts / reboots) */
	private static volatile Context sAppContext;
	private static boolean sEventsLoaded = false;

	static void initEventLog(Context context)
	{
		sAppContext = context.getApplicationContext();
		synchronized (sEvents)
		{
			if (sEventsLoaded) return;
			sEventsLoaded = true;
			String saved = PreferenceManager.getDefaultSharedPreferences(sAppContext)
				.getString(PREF_EVENT_LOG, "");
			List<String> previous = new ArrayList<>();
			for (String line : saved.split("\n")) if (!line.isEmpty()) previous.add(line);
			sEvents.addAll(0, previous);
			while (sEvents.size() > MAX_EVENTS) sEvents.removeFirst();
		}
	}

	static void logEvent(String text)
	{
		String line = android.text.format.DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis()) + "  " + text;
		String joined;
		synchronized (sEvents)
		{
			if (!sEvents.isEmpty() && sEvents.getLast().endsWith("  " + text)) return; // skip repeats
			sEvents.add(line);
			while (sEvents.size() > MAX_EVENTS) sEvents.removeFirst();
			joined = android.text.TextUtils.join("\n", sEvents);
		}
		Context ctx = sAppContext;
		if (ctx != null)
		{
			PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(PREF_EVENT_LOG, joined).apply();
		}
	}

	/** @return recent events, newest first */
	public static List<String> getEvents()
	{
		synchronized (sEvents)
		{
			List<String> result = new ArrayList<>(sEvents);
			Collections.reverse(result);
			return result;
		}
	}

	public static boolean isRunning() { return sRunning; }

	public static CommService.STATE getConnectionState() { return sConnState; }

	public static ElmProt.STAT getElmStatus() { return sElmStatus; }

	public static String getStatusMessage() { return sMessage; }

	public static long getLastDataMs() { return sLastDataMs; }

	public static String getDeviceName() { return sDeviceName; }

	/** Convenience: start (or poke) the service with an action */
	public static void start(Context context, String action)
	{
		Intent intent = new Intent(context, ObdBackgroundService.class).setAction(action);
		try
		{
			ContextCompat.startForegroundService(context, intent);
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "Unable to start service: " + e);
		}
	}

	/* ---------- instance ---------- */
	private final Handler handler = new Handler(Looper.getMainLooper());
	private SharedPreferences prefs;
	private CommService commService;
	private PowerManager.WakeLock wakeLock;
	private MqttPublisher mqtt;
	private int retryCount = 0;
	private boolean stopping = false;
	private long connectStartMs = 0L;

	/** true while a (re)connect is scheduled — avoids stacking retries */
	private boolean connectPending = false;
	/** ECU RX filter set for this connection (0 = none) */
	private int ecuFilter = 0;
	/** time ECU detection finished (0 = not yet) */
	private long ecuDetectedMs = 0L;
	/** OBD data items were reported by the vehicle since ECU detection */
	private volatile boolean dataItemsSeen = false;

	private final Runnable connectRunnable = () -> {
		connectPending = false;
		connect();
	};

	/** Messages from CommService (connection state) */
	private final Handler commHandler = new Handler(Looper.getMainLooper())
	{
		@Override
		public void handleMessage(Message msg)
		{
			switch (msg.what)
			{
				case CommService.MESSAGE_STATE_CHANGE:
					onConnectionState((CommService.STATE) msg.obj);
					break;
				case CommService.MESSAGE_DEVICE_NAME:
					String name = msg.getData().getString(CommService.DEVICE_NAME);
					if (name != null) sDeviceName = name;
					break;
				default:
					break;
			}
		}
	};

	/** ELM protocol status / ECU detection (called on the protocol thread) */
	private final PropertyChangeListener elmListener = new PropertyChangeListener()
	{
		@Override
		public void propertyChange(final PropertyChangeEvent evt)
		{
			handler.post(() -> onElmPropertyChange(evt));
		}
	};

	/** A value of any OBD data item changed = the link is alive */
	private final PvChangeListener valueListener = new PvChangeListener()
	{
		@Override
		public void pvChanged(PvChangeEvent event)
		{
			sLastDataMs = System.currentTimeMillis();
		}
	};

	/**
	 * New data items were added to the list → watch their values.
	 * (Value changes of child PVs are NOT forwarded to listeners of the list
	 * itself, so each item needs its own listener.)
	 */
	private final PvChangeListener dataListener = new PvChangeListener()
	{
		@Override
		public void pvChanged(PvChangeEvent event)
		{
			if ((event.getType() & PvChangeEvent.PV_ADDED) == 0) return;
			dataItemsSeen = true;
			sLastDataMs = System.currentTimeMillis();
			Object value = event.getValue();
			if (value instanceof Object[])
			{
				for (Object o : (Object[]) value) watch(o);
			}
			else
			{
				watch(value);
			}
		}

		private void watch(Object o)
		{
			if (o instanceof ProcessVar)
			{
				((ProcessVar) o).addPvChangeListener(valueListener, PvChangeEvent.PV_MODIFIED);
			}
		}
	};

	/** Bluetooth switched on → connect right away */
	/**
	 * Head units usually go to standby instead of shutting down, so there is no
	 * BOOT_COMPLETED when the car starts. Screen on / unlock / the adapter's BT
	 * link coming up are the signals that the car is back: connect right away
	 * (and drop a link that silently died during standby).
	 */
	private final BroadcastReceiver wakeReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			String action = intent.getAction();
			if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action))
			{
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				String saved = prefs.getString(PREF_DEVICE_ADDRESS, null);
				if (device == null || saved == null || !saved.equalsIgnoreCase(device.getAddress())) return;
				kick("OBD 裝置藍牙已連上");
			}
			else
			{
				kick(Intent.ACTION_SCREEN_ON.equals(action) ? "螢幕開啟" : "使用者解鎖");
			}
		}
	};

	/** Something suggests the car is (back) on: make sure we are connected and data flows */
	private void kick(String reason)
	{
		if (stopping || commService == null) return;
		CommService.STATE state = commService.getState();
		if (state == CommService.STATE.CONNECTED)
		{
			if (System.currentTimeMillis() - sLastDataMs > 20000)
			{
				logEvent(reason + " → 連線沒有資料，重新連線");
				commService.stop(); // → OFFLINE → retry
			}
		}
		else if (state != CommService.STATE.CONNECTING)
		{
			logEvent(reason + " → 立即連線");
			retryCount = 0;
			scheduleConnect(1000);
		}
	}

	private final BroadcastReceiver btStateReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
			if (state == BluetoothAdapter.STATE_ON)
			{
				log.info("Bluetooth turned on → connect");
				retryCount = 0;
				scheduleConnect(2000);
			}
			else if (state == BluetoothAdapter.STATE_OFF)
			{
				setMessage("藍牙已關閉，等待開啟…");
			}
		}
	};

	private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
		(sharedPreferences, key) -> {
			applyElmSettings(key);
			if (PREF_KEEP_AWAKE.equals(key)) updateWakeLock();
			if (PREF_COMM_MEDIUM.equals(key)) recreateCommService();
		};

	/** Periodic health check: stuck connects and dead links */
	private final Runnable watchdog = new Runnable()
	{
		@Override
		public void run()
		{
			try
			{
				checkHealth();
			}
			finally
			{
				handler.postDelayed(this, WATCHDOG_PERIOD);
			}
		}
	};

	/* ======================= lifecycle ======================= */

	@Override
	public void onCreate()
	{
		super.onCreate();
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		initEventLog(this);
		logEvent("背景服務啟動");
		createNotificationChannel();
		startInForeground("啟動中…");

		applyElmSettings(null);
		CommService.elm.addPropertyChangeListener(elmListener);
		ObdProt.PidPvs.addPvChangeListener(dataListener, PvChangeEvent.PV_ADDED);
		prefs.registerOnSharedPreferenceChangeListener(prefListener);
		registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
		IntentFilter wakeFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		wakeFilter.addAction(Intent.ACTION_USER_PRESENT);
		wakeFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
		registerReceiver(wakeReceiver, wakeFilter);

		mqtt = MqttPublisher.get(this);
		mqtt.start(this::snapshot);

		updateWakeLock();
		handler.postDelayed(watchdog, WATCHDOG_PERIOD);
		sRunning = true;
		log.info("ObdBackgroundService created");
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		// must be called quickly after startForegroundService()
		startInForeground(sMessage.isEmpty() ? "啟動中…" : sMessage);

		String action = intent != null ? intent.getAction() : null;
		log.info("onStartCommand action=" + action + " state=" + sConnState);

		if (ACTION_STOP.equals(action))
		{
			prefs.edit().putBoolean(PREF_SERVICE_ENABLED, false).apply();
			stopping = true;
			stopSelf();
			return START_NOT_STICKY;
		}

		prefs.edit().putBoolean(PREF_SERVICE_ENABLED, true).apply();
		stopping = false;

		if (commService == null)
		{
			recreateCommService();
		}
		if (ACTION_RECONNECT.equals(action))
		{
			// drop a current link (possibly to another device) and connect anew
			retryCount = 0;
			if (sConnState == CommService.STATE.CONNECTED || sConnState == CommService.STATE.CONNECTING)
			{
				disconnectQuietly();
			}
			scheduleConnect(500);
		}
		else if (sConnState != CommService.STATE.CONNECTED && sConnState != CommService.STATE.CONNECTING)
		{
			retryCount = 0;
			scheduleConnect(1000);
		}
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return null;
	}

	@Override
	public void onDestroy()
	{
		stopping = true;
		handler.removeCallbacksAndMessages(null);
		try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) { }
		try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) { }
		prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
		if (mqtt != null)
		{
			mqtt.publishStatus("offline");
			mqtt.stop();
		}
		CommService.elm.removePropertyChangeListener(elmListener);
		ObdProt.PidPvs.removePvChangeListener(dataListener);
		for (Object o : currentPvs())
		{
			if (o instanceof ProcessVar) ((ProcessVar) o).removePvChangeListener(valueListener);
		}
		if (commService != null)
		{
			try { CommService.elm.goToSleep(); } catch (Exception ignored) { }
			commService.stop();
			commService = null;
		}
		releaseWakeLock();
		sRunning = false;
		sConnState = CommService.STATE.NONE;
		sElmStatus = ElmProt.STAT.UNDEFINED;
		sMessage = "服務已停止";
		log.info("ObdBackgroundService destroyed");
		super.onDestroy();
	}

	/* ======================= connection ======================= */

	private void recreateCommService()
	{
		if (commService != null)
		{
			disconnectQuietly();
			commService = null;
		}
		CommService.medium = getMedium();
		if (CommService.medium == CommService.MEDIUM.NETWORK)
		{
			commService = new NetworkCommService(this, commHandler);
		}
		else
		{
			if (BluetoothAdapter.getDefaultAdapter() == null)
			{
				setMessage("此裝置沒有藍牙");
				return;
			}
			commService = new BtCommService(this, commHandler);
		}
		sConnState = CommService.STATE.NONE;
	}

	/** Stop the current link without triggering an automatic retry storm */
	private void disconnectQuietly()
	{
		handler.removeCallbacks(connectRunnable);
		connectPending = false;
		if (commService != null)
		{
			commService.stop();
		}
	}

	private void scheduleConnect(long delayMs)
	{
		handler.removeCallbacks(connectRunnable);
		connectPending = !stopping;
		if (!stopping)
		{
			handler.postDelayed(connectRunnable, delayMs);
		}
	}

	private void scheduleRetry()
	{
		long delay = RETRY_DELAYS[Math.min(retryCount, RETRY_DELAYS.length - 1)];
		retryCount++;
		setMessage(String.format("未連線，%d 秒後重試（第 %d 次）", delay / 1000, retryCount));
		scheduleConnect(delay);
	}

	private void connect()
	{
		if (stopping) return;
		if (commService == null) recreateCommService();
		if (commService == null) return;

		CommService.STATE state = commService.getState();
		if (state == CommService.STATE.CONNECTED || state == CommService.STATE.CONNECTING)
		{
			return;
		}

		try
		{
			if (CommService.medium == CommService.MEDIUM.NETWORK)
			{
				String host = prefs.getString(PREF_NET_ADDRESS, "192.168.0.10");
				int port = getPrefsInt(PREF_NET_PORT, 35000);
				setMessage("連線中 " + host + ":" + port + " …");
				connectStartMs = System.currentTimeMillis();
				((NetworkCommService) commService).connect(host, port);
				return;
			}

			// ----- Bluetooth -----
			if (Build.VERSION.SDK_INT >= 31
				&& ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
				!= PackageManager.PERMISSION_GRANTED)
			{
				setMessage("缺少藍牙權限，請開啟 App 授權");
				return; // UI will restart us after permission was granted
			}
			BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
			if (adapter == null)
			{
				setMessage("此裝置沒有藍牙");
				return;
			}
			if (!adapter.isEnabled())
			{
				// btStateReceiver reconnects as soon as BT is switched on
				setMessage("藍牙已關閉，等待開啟…");
				return;
			}
			String address = prefs.getString(PREF_DEVICE_ADDRESS, null);
			if (address == null || !BluetoothAdapter.checkBluetoothAddress(address))
			{
				// nothing selected yet → try to find the OBD adapter among paired devices
				address = autoSelectDevice(this);
				if (address == null)
				{
					setMessage("找不到已配對的 OBD 裝置，請先在系統藍牙配對，或按「選擇 OBD 藍牙裝置」");
					scheduleConnect(RETRY_DELAYS[RETRY_DELAYS.length - 1]); // maybe it gets paired later
					return;
				}
			}
			BluetoothDevice device = adapter.getRemoteDevice(address);
			String name = prefs.getString(PREF_DEVICE_NAME, address);
			setMessage("連線中 " + name + " …");
			connectStartMs = System.currentTimeMillis();
			commService.connect(device, prefs.getBoolean(PREF_BT_SECURE, false));
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "connect failed", e);
			scheduleRetry();
		}
	}

	private void onConnectionState(CommService.STATE state)
	{
		CommService.STATE previous = sConnState;
		sConnState = state;
		log.info("Connection state " + previous + " -> " + state);
		logEvent("連線狀態 " + previous + " → " + state);
		switch (state)
		{
			case CONNECTED:
				handler.removeCallbacks(connectRunnable);
				connectPending = false;
				retryCount = 0;
				sLastDataMs = System.currentTimeMillis(); // grace period for the watchdog
				ecuFilter = 0;
				ecuDetectedMs = 0;
				dataItemsSeen = false;
				setMessage("已連線，初始化 ELM…");
				// reset + init the ELM adapter → ECU detection → (elmListener) start data polling
				CommService.elm.reset();
				break;

			case CONNECTING:
				break;

			default:
				// OFFLINE / NONE / LISTEN — connection failed or lost
				if (previous == CommService.STATE.CONNECTED && mqtt != null)
				{
					mqtt.publishStatus("offline");
				}
				sElmStatus = ElmProt.STAT.DISCONNECTED;
				if (!stopping && !connectPending)
				{
					scheduleRetry();
				}
				break;
		}
	}

	/** Name fragments of common ELM327 / OBD adapters (upper case) */
	private static final String[] OBD_NAME_HINTS = {
		"OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI",
		"KW9", "CARISTA"
	};

	/**
	 * Find the OBD adapter among the paired Bluetooth devices by name and remember it.
	 * @return address of the selected device, or null if none looks like an OBD adapter
	 */
	@android.annotation.SuppressLint("MissingPermission")
	static String autoSelectDevice(Context context)
	{
		try
		{
			BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
			if (adapter == null || !adapter.isEnabled()) return null;
			if (Build.VERSION.SDK_INT >= 31
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
				!= PackageManager.PERMISSION_GRANTED) return null;
			for (String hint : OBD_NAME_HINTS) // ordered by how specific the hint is
			{
				for (BluetoothDevice device : adapter.getBondedDevices())
				{
					String name = device.getName();
					if (name != null && name.toUpperCase(java.util.Locale.ROOT).contains(hint))
					{
						PreferenceManager.getDefaultSharedPreferences(context).edit()
							.putString(PREF_DEVICE_ADDRESS, device.getAddress())
							.putString(PREF_DEVICE_NAME, name)
							.apply();
						logEvent("自動選擇 OBD 裝置：" + name + " (" + device.getAddress() + ")");
						return device.getAddress();
					}
				}
			}
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "auto select device failed", e);
		}
		return null;
	}

	/* ======================= ELM / OBD ======================= */

	@SuppressWarnings("unchecked")
	private void onElmPropertyChange(PropertyChangeEvent evt)
	{
		String prop = evt.getPropertyName();
		if (ElmProt.PROP_ECU_ADDRESS.equals(prop))
		{
			selectEcu(new HashSet<>((Set<Integer>) evt.getNewValue()));
		}
		else if (ElmProt.PROP_STATUS.equals(prop))
		{
			ElmProt.STAT status = (ElmProt.STAT) evt.getNewValue();
			sElmStatus = status;
			logEvent("ELM 狀態 → " + status);
			if (sConnState != CommService.STATE.CONNECTED) return;

			if (status == ElmProt.STAT.ECU_DETECTED)
			{
				ecuDetectedMs = System.currentTimeMillis();
				dataItemsSeen = false;
				// THE missing step: start reading live data (service 01) right away,
				// no UI needed. Includes odometer / distance PIDs if the car supports them.
				startDataPolling("偵測到 ECU → 開始讀取 OBD 資料");
			}
			else
			{
				// Some adapters never reach ECU detection (init ends in another state)
				// and the ELM then sits idle. Once the status has been stable for a few
				// seconds without data polling, start it anyway — this is what opening
				// the "OBD data" page did in the old app.
				handler.removeCallbacks(dataStartFallback);
				handler.postDelayed(dataStartFallback, DATA_START_FALLBACK_DELAY);
			}
			setMessage("已連線 " + (sDeviceName != null ? sDeviceName : "") + " — " + status);
		}
	}

	private static final long DATA_START_FALLBACK_DELAY = 5000;

	private final Runnable dataStartFallback = () -> {
		if (stopping || sConnState != CommService.STATE.CONNECTED) return;
		if (CommService.elm.getService() != ObdProt.OBD_SVC_NONE) return;
		ElmProt.STAT st = CommService.elm.getStatus();
		if (st == ElmProt.STAT.INITIALIZING || st == ElmProt.STAT.ECU_DETECT
			|| st == ElmProt.STAT.CONNECTING) return; // still busy, a later status change re-arms us
		startDataPolling("ELM 未進入 ECU 偵測（" + st + "）→ 直接開始讀取 OBD 資料");
	};

	private void startDataPolling(String reason)
	{
		handler.removeCallbacks(dataStartFallback);
		if (CommService.elm.getService() != ObdProt.OBD_SVC_DATA)
		{
			log.info(reason);
			logEvent(reason);
			CommService.elm.setService(ObdProt.OBD_SVC_DATA, true);
		}
		if (mqtt != null)
		{
			mqtt.resetChangeTracking();
			mqtt.publishStatus("online");
		}
	}

	/**
	 * Several ECUs answered: use the remembered one, otherwise the lowest address
	 * (normally the engine ECU, e.g. 0x7E8). No dialog — this runs unattended.
	 */
	private void selectEcu(Set<Integer> addresses)
	{
		ecuFilter = 0;
		StringBuilder list = new StringBuilder();
		for (Integer a : addresses) list.append(String.format(" 0x%X", a));
		logEvent("偵測到 " + addresses.size() + " 個 ECU:" + list);
		if (addresses.isEmpty())
		{
			logEvent("車輛沒有回應 — 請確認引擎已發動 / 電門 ON");
			return;
		}
		if (addresses.size() <= 1) return;
		// Only filter on an ECU the user explicitly picked earlier. Without a filter
		// all ECUs answer (functional addressing), which always works; a wrong filter
		// would block every response ("NO DATA" forever).
		int preferred = prefs.getInt(PREF_ECU_ADDRESS, 0);
		if (preferred != 0 && addresses.contains(preferred))
		{
			ecuFilter = preferred;
			logEvent(String.format("使用先前選擇的 ECU 0x%X", preferred));
			CommService.elm.setEcuAddress(preferred);
		}
	}

	/** Snapshot of current OBD values for MQTT (called on the MQTT thread) */
	private Map<String, String> snapshot()
	{
		Map<String, String> result = new LinkedHashMap<>();
		if (sConnState != CommService.STATE.CONNECTED
			|| System.currentTimeMillis() - sLastDataMs > DATA_FRESHNESS)
		{
			return result;
		}
		List<Object> pvs = currentPvs();
		List<String> names = new ArrayList<>();
		for (Object o : pvs)
		{
			if (!(o instanceof EcuDataPv)) continue;
			EcuDataPv pv = (EcuDataPv) o;
			Object mnemonic = pv.get(EcuDataPv.FID_MNEMONIC);
			Object value = pv.get(EcuDataPv.FIELDS[EcuDataPv.FID_VALUE]);
			if (mnemonic == null || value == null) continue;
			result.put(mnemonic.toString(), value.toString());
			names.add(mnemonic.toString());
		}
		if (mqtt != null && !names.isEmpty())
		{
			mqtt.addKnownItems(names);
		}
		return result;
	}

	/**
	 * Copy the PV list under the list lock only (do NOT touch the PVs while holding
	 * it: the protocol thread locks PV → list, so list → PV here could deadlock).
	 */
	private static List<Object> currentPvs()
	{
		synchronized (ObdProt.PidPvs)
		{
			return new ArrayList<Object>(ObdProt.PidPvs.values());
		}
	}

	/** Current values sorted by description, for the status screen */
	public static Map<String, String> displayValues()
	{
		TreeMap<String, String> result = new TreeMap<>();
		for (Object o : currentPvs())
		{
			if (!(o instanceof EcuDataPv)) continue;
			EcuDataPv pv = (EcuDataPv) o;
			Object mnemonic = pv.get(EcuDataPv.FID_MNEMONIC);
			Object value = pv.get(EcuDataPv.FIELDS[EcuDataPv.FID_VALUE]);
			if (mnemonic == null || value == null) continue;
			Object descr = pv.get(EcuDataPv.FIELDS[EcuDataPv.FID_DESCRIPT]);
			String units = pv.getUnits();
			String label = (descr != null ? descr.toString() : mnemonic.toString());
			result.put(label, value + (units != null && !units.isEmpty() ? " " + units : "")
				+ "   [" + mnemonic + "]");
		}
		return result;
	}

	private void checkHealth()
	{
		if (stopping || commService == null) return;
		long now = System.currentTimeMillis();
		CommService.STATE state = commService.getState();
		if (state == CommService.STATE.CONNECTING && now - connectStartMs > CONNECT_TIMEOUT)
		{
			log.warning("Connect timeout → abort and retry");
			logEvent("連線逾時 → 中斷並重試");
			commService.stop(); // → OFFLINE → retry
		}
		else if (state == CommService.STATE.CONNECTED && ecuFilter != 0 && ecuDetectedMs > 0
			&& !dataItemsSeen && now - ecuDetectedMs > 30000)
		{
			// the remembered ECU filter blocks all answers → forget it, reconnect unfiltered
			logEvent(String.format("ECU 0x%X 沒有回應資料 → 取消 ECU 篩選並重新連線", ecuFilter));
			prefs.edit().remove(PREF_ECU_ADDRESS).apply();
			ecuFilter = 0;
			ecuDetectedMs = 0;
			commService.stop(); // → OFFLINE → retry
		}
		else if (state == CommService.STATE.CONNECTED && now - sLastDataMs > DATA_TIMEOUT)
		{
			log.warning("No OBD data for " + (now - sLastDataMs) / 1000 + "s → reconnect");
			setMessage("長時間沒有資料，重新連線…");
			commService.stop(); // → OFFLINE → retry
		}
	}

	/* ======================= settings ======================= */

	private CommService.MEDIUM getMedium()
	{
		int idx = getPrefsInt(PREF_COMM_MEDIUM, 0);
		// USB support was removed — anything else than NETWORK means Bluetooth
		return idx == CommService.MEDIUM.NETWORK.ordinal()
			? CommService.MEDIUM.NETWORK : CommService.MEDIUM.BLUETOOTH;
	}

	/** Apply ELM protocol related settings (formerly done by MainActivity) */
	private void applyElmSettings(String key)
	{
		try
		{
			if (key == null || PREF_ADAPTIVE_TIMING.equals(key))
			{
				CommService.elm.mAdaptiveTiming.setMode(ElmProt.AdaptTimingMode.valueOf(
					prefs.getString(PREF_ADAPTIVE_TIMING, ElmProt.AdaptTimingMode.OFF.toString())));
			}
			if (key == null || PREF_CUSTOM_INIT.equals(key))
			{
				String value = prefs.getString(PREF_CUSTOM_INIT, null);
				if (value != null && !value.trim().isEmpty())
				{
					CommService.elm.setCustomInitCommands(value.split("\n"));
				}
			}
			if (key == null || PREF_PROTOCOL.equals(key))
			{
				ElmProt.setPreferredProtocol(getPrefsInt(PREF_PROTOCOL, 0));
			}
			if (key == null || PREF_ELM_CMD_DISABLE.equals(key))
			{
				// commands the user disabled in the old app (e.g. for clone adapters)
				Set<String> disabled = prefs.getStringSet(PREF_ELM_CMD_DISABLE, null);
				ElmProt.disableCommands(disabled);
				if (key == null && disabled != null && !disabled.isEmpty())
				{
					logEvent("已停用 ELM 指令：" + disabled);
				}
			}
			if (key == null || PREF_ELM_RESET_ON_NRC.equals(key))
			{
				CommService.elm.setResetOnNrc(prefs.getBoolean(PREF_ELM_RESET_ON_NRC, false));
			}
			if (key == null || PREF_ELM_MIN_TIMEOUT.equals(key))
			{
				CommService.elm.mAdaptiveTiming.setElmTimeoutMin(getPrefsInt(PREF_ELM_MIN_TIMEOUT,
					CommService.elm.mAdaptiveTiming.getElmTimeoutMin()));
			}
			if (key == null || PREF_DATA_DISABLE_MAX.equals(key))
			{
				EcuDataItem.MAX_ERROR_COUNT = getPrefsInt(PREF_DATA_DISABLE_MAX, 3);
			}
			if (key == null || PREF_MEASURE_SYSTEM.equals(key))
			{
				EcuDataItem.cnvSystem = getPrefsInt(PREF_MEASURE_SYSTEM, EcuDataItem.SYSTEM_METRIC);
			}
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "Applying ELM settings failed", e);
		}
	}

	private int getPrefsInt(String key, int defaultValue)
	{
		try
		{
			return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)).trim());
		}
		catch (Exception e)
		{
			return defaultValue;
		}
	}

	/* ======================= wake lock / notification ======================= */

	/**
	 * Keep the CPU running so reconnect timers and polling keep working while the
	 * screen is off (Handler delays stop in deep sleep → "sometimes no auto-connect").
	 */
	private void updateWakeLock()
	{
		if (prefs.getBoolean(PREF_KEEP_AWAKE, true))
		{
			if (wakeLock == null)
			{
				PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
				wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndrOBD:obd");
				wakeLock.setReferenceCounted(false);
			}
			if (!wakeLock.isHeld()) wakeLock.acquire();
		}
		else
		{
			releaseWakeLock();
		}
	}

	private void releaseWakeLock()
	{
		if (wakeLock != null && wakeLock.isHeld())
		{
			wakeLock.release();
		}
	}

	private void setMessage(String message)
	{
		sMessage = message;
		logEvent(message);
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager != null && !stopping)
		{
			manager.notify(NOTIFICATION_ID, createNotification(message));
		}
	}

	private void startInForeground(String text)
	{
		Notification notification = createNotification(text);
		try
		{
			boolean hasBtPermission = Build.VERSION.SDK_INT < 31
				|| ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
				== PackageManager.PERMISSION_GRANTED;
			if (Build.VERSION.SDK_INT >= 29 && hasBtPermission)
			{
				startForeground(NOTIFICATION_ID, notification,
					android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
			}
			else
			{
				startForeground(NOTIFICATION_ID, notification);
			}
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "startForeground failed", e);
			try { startForeground(NOTIFICATION_ID, notification); } catch (Exception ignored) { }
		}
	}

	private void createNotificationChannel()
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID, "OBD Service", NotificationManager.IMPORTANCE_LOW);
			channel.setDescription("OBD → Home Assistant");
			channel.setShowBadge(false);
			NotificationManager manager = getSystemService(NotificationManager.class);
			if (manager != null) manager.createNotificationChannel(channel);
		}
	}

	private Notification createNotification(String text)
	{
		Intent open = new Intent(this, MainActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
		return new NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("AndrOBD → Home Assistant")
			.setContentText(text)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentIntent(pendingIntent)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build();
	}
}
