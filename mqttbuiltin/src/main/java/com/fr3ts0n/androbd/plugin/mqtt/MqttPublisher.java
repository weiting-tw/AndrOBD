package com.fr3ts0n.androbd.plugin.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-process MQTT publisher (Home Assistant feed).
 * <p>
 * 以前是透過 AndrOBD plugin 框架（broadcast + 外掛 Service）餵資料，而資料又是由
 * 「OBD 資料頁」的 list adapter 送出 — 沒打開那頁就不會送。現在改成由背景服務直接呼叫：
 * 每 {@code update_period} 秒向 {@link DataSource} 取一次最新快照，只送有變動的
 * topic；超過 {@code heartbeat_period} 秒則整批重送。
 */
public final class MqttPublisher implements SharedPreferences.OnSharedPreferenceChangeListener
{
	private static final String TAG = "MqttPublisher";

	/** Preference keys (unchanged from the former plugin so existing settings keep working) */
	static final String MQTT_PREFIX = "mqtt_prefix";
	static final String UPDATE_PERIOD = "update_period";
	static final String HEARTBEAT_PERIOD = "heartbeat_period";
	static final String MQTT_PROTOCOL = "mqtt_protocol";
	static final String MQTT_HOSTNAME = "mqtt_hostname";
	static final String MQTT_PORT = "mqtt_port";
	static final String MQTT_USERNAME = "mqtt_username";
	static final String MQTT_PASSWORD = "mqtt_password";
	static final String MQTT_CLIENTID = "mqtt_clientid";
	static final String MQTT_RETAIN = "mqtt_retain";
	static final String MQTT_QOS = "mqtt_qos";
	static final String ITEMS_SELECTED = "data_items";
	static final String ITEMS_KNOWN = "known_items";

	/** Topic (below prefix) carrying the OBD connection state: online / offline */
	public static final String STATUS_TOPIC = "status";

	/** Supplier of the current measurement snapshot (mnemonic -> value) */
	public interface DataSource
	{
		/** @return current values, or an empty map if there is no fresh data */
		Map<String, String> snapshot();
	}

	private static MqttPublisher instance;

	public static synchronized MqttPublisher get(Context context)
	{
		if (instance == null)
		{
			instance = new MqttPublisher(context.getApplicationContext());
		}
		return instance;
	}

	private final Context context;
	private final SharedPreferences prefs;
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> cyclicTask;
	private DataSource dataSource;

	/** Last successfully published value per topic key (publish-on-change) */
	private final HashMap<String, String> lastPublished = new HashMap<>();
	private long lastFullPublishMs = 0L;

	// settings
	private String prefix;
	private int updatePeriod;
	private int heartbeatPeriod;
	private String brokerProtocol;
	private String brokerHostName;
	private int brokerPort;
	private String username;
	private String password;
	private String clientId;
	private boolean retain;
	private int qos;
	private Set<String> selectedItems = new HashSet<>();

	// status for the UI
	private volatile long lastPublishMs = 0L;
	private volatile int lastPublishCount = 0;
	private volatile String lastError = null;

	private MqttPublisher(Context context)
	{
		this.context = context;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		readSettings();
		if (clientId == null || clientId.trim().isEmpty())
		{
			clientId = MqttClient.generateClientId();
			prefs.edit().putString(MQTT_CLIENTID, clientId).apply();
		}
		prefs.registerOnSharedPreferenceChangeListener(this);
	}

	/** Start cyclic publishing of the given data source */
	public synchronized void start(DataSource source)
	{
		dataSource = source;
		if (executor == null || executor.isShutdown())
		{
			executor = Executors.newSingleThreadScheduledExecutor();
		}
		reschedule();
	}

	/** Stop cyclic publishing */
	public synchronized void stop()
	{
		if (cyclicTask != null)
		{
			cyclicTask.cancel(false);
			cyclicTask = null;
		}
		if (executor != null)
		{
			executor.shutdown();
			executor = null;
		}
		dataSource = null;
	}

	private synchronized void reschedule()
	{
		if (executor == null) return;
		if (cyclicTask != null) cyclicTask.cancel(false);
		long period = Math.max(1, updatePeriod);
		// first publish shortly after start so Home Assistant gets data quickly
		cyclicTask = executor.scheduleWithFixedDelay(this::publishCycle, 5, period, TimeUnit.SECONDS);
	}

	/** Trigger an immediate publish cycle (e.g. right after OBD data becomes available) */
	public synchronized void publishSoon()
	{
		if (executor != null)
		{
			executor.execute(this::publishCycle);
		}
	}

	/** Publish the OBD connection status (retained) so Home Assistant can track availability */
	public synchronized void publishStatus(final String status)
	{
		if (executor != null)
		{
			executor.execute(() -> {
				HashMap<String, String> msg = new HashMap<>();
				msg.put(STATUS_TOPIC, status);
				send(msg, true);
			});
		}
	}

	/** Remember data item names so they can be selected in the MQTT settings */
	public void addKnownItems(Collection<String> items)
	{
		Set<String> known = new HashSet<>(prefs.getStringSet(ITEMS_KNOWN, new HashSet<>()));
		if (known.addAll(items))
		{
			prefs.edit().putStringSet(ITEMS_KNOWN, known).apply();
		}
	}

	/** Forget previously published values so the next cycle sends a full snapshot */
	public void resetChangeTracking()
	{
		synchronized (lastPublished)
		{
			lastPublished.clear();
		}
	}

	public long getLastPublishMs() { return lastPublishMs; }

	public int getLastPublishCount() { return lastPublishCount; }

	public String getLastError() { return lastError; }

	public String getBrokerUrl() { return brokerProtocol + brokerHostName + ":" + brokerPort; }

	/** One publish cycle (runs on the executor thread) */
	private void publishCycle()
	{
		try
		{
			DataSource source = dataSource;
			if (source == null) return;
			Map<String, String> values = source.snapshot();
			if (values == null || values.isEmpty()) return;

			final long now = System.currentTimeMillis();
			final boolean heartbeatDue = heartbeatPeriod > 0
				&& (now - lastFullPublishMs) >= heartbeatPeriod * 1000L;

			Set<String> selected = selectedItems;
			HashMap<String, String> toPublish = new HashMap<>();
			synchronized (lastPublished)
			{
				for (Map.Entry<String, String> entry : values.entrySet())
				{
					String key = entry.getKey();
					if (!selected.isEmpty() && !selected.contains(key)) continue;
					String value = entry.getValue();
					if (heartbeatDue || !value.equals(lastPublished.get(key)))
					{
						toPublish.put(key, value);
					}
				}
			}
			if (toPublish.isEmpty())
			{
				Log.d(TAG, "No changes since last publish, skip");
				return;
			}
			if (send(toPublish, retain))
			{
				lastFullPublishMs = now;
			}
		}
		catch (Throwable t)
		{
			// never let an exception kill the scheduled task
			Log.e(TAG, "publish cycle failed", t);
			lastError = t.toString();
		}
	}

	/**
	 * Connect, publish all entries, disconnect.
	 * @return true if all entries were published
	 */
	private boolean send(Map<String, String> entries, boolean retainFlag)
	{
		final String url = getBrokerUrl();
		MqttClient client = null;
		try
		{
			Log.i(TAG, "Connect: " + url + " (" + entries.size() + " item(s))");
			client = new MqttClient(url, clientId, new MemoryPersistence());
			MqttConnectOptions options = new MqttConnectOptions();
			options.setConnectionTimeout(15);
			if (username != null && !username.trim().isEmpty())
			{
				options.setUserName(username);
				options.setPassword(password == null ? new char[0] : password.toCharArray());
			}
			client.connect(options);

			for (Map.Entry<String, String> entry : entries.entrySet())
			{
				String topic = prefix + entry.getKey();
				client.publish(topic, entry.getValue().getBytes(), qos, retainFlag);
				synchronized (lastPublished)
				{
					lastPublished.put(entry.getKey(), entry.getValue());
				}
			}
			client.disconnect();
			lastPublishMs = System.currentTimeMillis();
			lastPublishCount = entries.size();
			lastError = null;
			return true;
		}
		catch (MqttException e)
		{
			Log.e(TAG, "Publish failed: " + url, e);
			lastError = e.toString();
			return false;
		}
		finally
		{
			if (client != null)
			{
				try { client.close(); } catch (Exception ignored) { }
			}
		}
	}

	private static int getPrefsInt(SharedPreferences prefs, String key, int defaultValue)
	{
		try
		{
			return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)).trim());
		}
		catch (Exception ex)
		{
			return defaultValue;
		}
	}

	private void readSettings()
	{
		prefix = prefs.getString(MQTT_PREFIX, "AndrOBD/");
		updatePeriod = getPrefsInt(prefs, UPDATE_PERIOD, 30);
		heartbeatPeriod = getPrefsInt(prefs, HEARTBEAT_PERIOD, 300);
		brokerProtocol = prefs.getString(MQTT_PROTOCOL, context.getString(R.string.mqtt_prot_tcp));
		brokerHostName = prefs.getString(MQTT_HOSTNAME, "localhost");
		brokerPort = getPrefsInt(prefs, MQTT_PORT, 1883);
		username = prefs.getString(MQTT_USERNAME, "guest");
		password = prefs.getString(MQTT_PASSWORD, "guest");
		clientId = prefs.getString(MQTT_CLIENTID, "").replaceAll("\\s+", "-");
		qos = getPrefsInt(prefs, MQTT_QOS, 0);
		retain = prefs.getBoolean(MQTT_RETAIN, true);
		selectedItems = new HashSet<>(prefs.getStringSet(ITEMS_SELECTED, new HashSet<>()));
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		int oldPeriod = updatePeriod;
		readSettings();
		if (key == null || key.startsWith("mqtt_") || ITEMS_SELECTED.equals(key))
		{
			// broker / topic settings changed -> resend everything
			resetChangeTracking();
		}
		if (oldPeriod != updatePeriod)
		{
			reschedule();
		}
	}
}
