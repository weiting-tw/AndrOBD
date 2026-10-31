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
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.fr3ts0n.androbd.plugin.mqtt.MqttPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple status screen.
 * <p>
 * All work (connect, read OBD data, publish to Home Assistant) happens in
 * {@link ObdBackgroundService}; this screen only selects the device, shows the
 * status and opens the settings. Closing it does not affect the data flow.
 */
public class MainActivity extends Activity
{
	private static final int REQUEST_PERMISSIONS = 1;
	private static final int REQUEST_SELECT_DEVICE = 2;
	private static final int REQUEST_ENABLE_BT = 3;
	private static final long REFRESH_MS = 1000;

	private SharedPreferences prefs;
	private TextView statusTitle;
	private TextView statusDetails;
	private TextView valuesView;
	private TextView eventsView;
	private Button batteryButton;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private final Runnable refresher = new Runnable()
	{
		@Override
		public void run()
		{
			refresh();
			handler.postDelayed(this, REFRESH_MS);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		setTitle(getString(R.string.app_name) + " " + getString(R.string.app_version));
		prefs = PreferenceManager.getDefaultSharedPreferences(this);

		statusTitle = findViewById(R.id.status_title);
		statusDetails = findViewById(R.id.status_details);
		valuesView = findViewById(R.id.values);
		eventsView = findViewById(R.id.events);
		batteryButton = findViewById(R.id.btn_battery);

		findViewById(R.id.btn_select_device).setOnClickListener(v -> selectDevice());
		findViewById(R.id.btn_reconnect).setOnClickListener(v ->
			ObdBackgroundService.start(this, ObdBackgroundService.ACTION_RECONNECT));
		findViewById(R.id.btn_mqtt).setOnClickListener(v -> startActivity(
			new Intent(this, com.fr3ts0n.androbd.plugin.mqtt.SettingsActivity.class)));
		findViewById(R.id.btn_settings).setOnClickListener(v ->
			startActivity(new Intent(this, SettingsActivity.class)));
		batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
		findViewById(R.id.btn_stop).setOnClickListener(v -> {
			ObdBackgroundService.start(this, ObdBackgroundService.ACTION_STOP);
			Toast.makeText(this, "服務已停止，按「立即連線」可再啟動", Toast.LENGTH_LONG).show();
		});

		if (requestMissingPermissions())
		{
			onPermissionsReady();
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		handler.post(refresher);
	}

	@Override
	protected void onPause()
	{
		handler.removeCallbacks(refresher);
		super.onPause();
	}

	/* ---------------- permissions / startup ---------------- */

	/** @return true if all needed permissions are already granted */
	private boolean requestMissingPermissions()
	{
		List<String> needed = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= 31)
		{
			addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
			addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
		}
		if (Build.VERSION.SDK_INT >= 33)
		{
			addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
		}
		if (needed.isEmpty()) return true;
		if (Build.VERSION.SDK_INT >= 23)
		{
			requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
		}
		return false;
	}

	private void addIfMissing(List<String> list, String permission)
	{
		if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
		{
			list.add(permission);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_PERMISSIONS)
		{
			onPermissionsReady();
		}
	}

	private void onPermissionsReady()
	{
		if (!hasBtConnectPermission())
		{
			Toast.makeText(this, "需要藍牙權限才能連線 OBD 裝置", Toast.LENGTH_LONG).show();
		}
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if (isBluetoothMedium() && adapter != null && !adapter.isEnabled() && hasBtConnectPermission())
		{
			try
			{
				startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
			}
			catch (Exception ignored) { }
		}
		if (isBluetoothMedium()
			&& prefs.getString(ObdBackgroundService.PREF_DEVICE_ADDRESS, null) == null
			&& ObdBackgroundService.autoSelectDevice(this) == null)
		{
			// no paired device looks like an OBD adapter → let the user pick (only once)
			selectDevice();
		}
		// (re)start the background service — this is what keeps everything running
		ObdBackgroundService.start(this, ObdBackgroundService.ACTION_START);
	}

	private boolean hasBtConnectPermission()
	{
		return Build.VERSION.SDK_INT < 31
			|| ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
			== PackageManager.PERMISSION_GRANTED;
	}

	private boolean isBluetoothMedium()
	{
		return !String.valueOf(CommService.MEDIUM.NETWORK.ordinal())
			.equals(prefs.getString(ObdBackgroundService.PREF_COMM_MEDIUM, "0"));
	}

	/* ---------------- device selection ---------------- */

	private void selectDevice()
	{
		if (!isBluetoothMedium())
		{
			Toast.makeText(this, "目前使用 WiFi 連線，請在「連線設定」設定 IP / Port", Toast.LENGTH_LONG).show();
			return;
		}
		if (!hasBtConnectPermission())
		{
			requestMissingPermissions();
			return;
		}
		startActivityForResult(new Intent(this, BtDeviceListActivity.class), REQUEST_SELECT_DEVICE);
	}

	@SuppressLint("MissingPermission")
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_SELECT_DEVICE && resultCode == RESULT_OK && data != null)
		{
			String address = data.getStringExtra(BtDeviceListActivity.EXTRA_DEVICE_ADDRESS);
			if (address == null) return;
			String name = address;
			try
			{
				BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
				if (device.getName() != null) name = device.getName();
			}
			catch (Exception ignored) { }
			prefs.edit()
				.putString(ObdBackgroundService.PREF_DEVICE_ADDRESS, address)
				.putString(ObdBackgroundService.PREF_DEVICE_NAME, name)
				.apply();
			ObdBackgroundService.start(this, ObdBackgroundService.ACTION_RECONNECT);
		}
		else if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK)
		{
			ObdBackgroundService.start(this, ObdBackgroundService.ACTION_START);
		}
	}

	/* ---------------- battery optimization ---------------- */

	private boolean isIgnoringBatteryOptimizations()
	{
		if (Build.VERSION.SDK_INT < 23) return true;
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
	}

	@SuppressLint("BatteryLife")
	private void requestIgnoreBatteryOptimizations()
	{
		if (Build.VERSION.SDK_INT < 23) return;
		try
		{
			Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
				Uri.parse("package:" + getPackageName()));
			startActivity(intent);
		}
		catch (Exception e)
		{
			startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
		}
	}

	/* ---------------- status ---------------- */

	private void refresh()
	{
		CommService.STATE state = ObdBackgroundService.getConnectionState();
		boolean running = ObdBackgroundService.isRunning();
		boolean receiving = state == CommService.STATE.CONNECTED
			&& System.currentTimeMillis() - ObdBackgroundService.getLastDataMs() < 30000;

		String title;
		if (!running) title = "⏹ 服務未執行";
		else if (receiving) title = "🟢 已連線，資料傳送中";
		else if (state == CommService.STATE.CONNECTED) title = "🟡 已連線，初始化中";
		else if (state == CommService.STATE.CONNECTING) title = "🟡 連線中…";
		else title = "🔴 未連線（自動重試中）";
		statusTitle.setText(title);

		StringBuilder sb = new StringBuilder();
		if (isBluetoothMedium())
		{
			sb.append("裝置：").append(prefs.getString(ObdBackgroundService.PREF_DEVICE_NAME, "(未選擇)"))
				.append("  ").append(prefs.getString(ObdBackgroundService.PREF_DEVICE_ADDRESS, "")).append('\n');
		}
		else
		{
			sb.append("裝置：WiFi ").append(prefs.getString(ObdBackgroundService.PREF_NET_ADDRESS, ""))
				.append(':').append(prefs.getString(ObdBackgroundService.PREF_NET_PORT, "")).append('\n');
		}
		sb.append("狀態：").append(ObdBackgroundService.getStatusMessage()).append('\n');
		sb.append("ELM：").append(ObdBackgroundService.getElmStatus()).append('\n');
		long lastData = ObdBackgroundService.getLastDataMs();
		sb.append("最後收到資料：").append(formatTime(lastData)).append('\n');

		MqttPublisher mqtt = MqttPublisher.get(this);
		sb.append("MQTT：").append(mqtt.getBrokerUrl()).append('\n');
		sb.append("最後送出：").append(formatTime(mqtt.getLastPublishMs()));
		if (mqtt.getLastPublishMs() > 0) sb.append("（").append(mqtt.getLastPublishCount()).append(" 筆）");
		if (mqtt.getLastError() != null) sb.append("\n⚠ MQTT 錯誤：").append(mqtt.getLastError());
		statusDetails.setText(sb);

		batteryButton.setVisibility(isIgnoringBatteryOptimizations()
			? android.view.View.GONE : android.view.View.VISIBLE);

		StringBuilder values = new StringBuilder();
		for (Map.Entry<String, String> entry : ObdBackgroundService.displayValues().entrySet())
		{
			values.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
		}
		valuesView.setText(values.length() > 0 ? values : "（尚無資料）");

		StringBuilder events = new StringBuilder();
		for (String line : ObdBackgroundService.getEvents())
		{
			events.append(line).append('\n');
		}
		eventsView.setText(events.length() > 0 ? events : "（無）");
	}

	private String formatTime(long ms)
	{
		if (ms <= 0) return "—";
		return DateFormat.format("HH:mm:ss", ms).toString();
	}
}
