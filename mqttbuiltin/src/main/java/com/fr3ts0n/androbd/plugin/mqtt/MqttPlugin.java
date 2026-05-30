package com.fr3ts0n.androbd.plugin.mqtt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.fr3ts0n.androbd.plugin.Plugin;
import com.fr3ts0n.androbd.plugin.PluginInfo;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * AndrOBD MQTT publishing plugin
 * <p>
 * Publish AndrOBD measurements to MQTT broker
 */

public class MqttPlugin
	extends Plugin
	implements Plugin.ConfigurationHandler,
		           Plugin.ActionHandler,
		           Plugin.DataReceiver,
		           SharedPreferences.OnSharedPreferenceChangeListener
{
	static final PluginInfo myInfo = new PluginInfo("MqttPublisher",
		MqttPlugin.class,
		"MQTT publish AndrOBD measurements",
		"Copyright (C) 2017 by fr3ts0n",
		"GPLV3+",
		"https://github.com/fr3ts0n/AndrOBD"
	);
	
	/**
	 * Preference keys
	 */
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
	
	/**
	 * The data collection
	 */
	static final HashMap<String, String> valueMap = new HashMap<>();
	/**
	 * Last successfully published value per topic — 用來做 publish-on-change diff。
	 * 跟 valueMap 同 lifecycle：onDataListUpdate 清掉時兩個都清。
	 */
	static final HashMap<String, String> lastPublishedMap = new HashMap<>();
	
	SharedPreferences prefs;
	String mqtt_prefix = "";
	
	/*
	  MQTT communication parameter
	 */
	
	/**
	 * MQTT protocol ( tcp:// | ssl:// )
	 */
	String brokerProtocol;
	/**
	 * MQTT host name / IP address
	 */
	String brokerHostName;
	/**
	 * MQTT broker port number
	 */
	int brokerPortNumber;
	/**
	 * MQTT login name
	 */
	String mUsername;
	/**
	 * MQTT login password
	 */
	String mPassword;
	/**
	 * Retain preselection value
	 */
	boolean mRetain = true;
	/**
	 * QOS preselection value
	 */
	int mQos = 0;
	/**
	 * set of items which are known to the plugin
	 */
	protected static HashSet<String> mKnownItems = new HashSet<>();
	/**
	 * set of items to be published
	 */
	HashSet<String> mSelectedItems = new HashSet<>();
	/**
	 * MQTT client id
	 */
	String mClientId = null;
	/**
	 * Period between publishing updates
	 */
	int update_period = 30;
	/**
	 * Heartbeat period in seconds. 即使值沒變、超過這個秒數也會強制 publish 一次完整快照、
	 * 讓 MQTT subscriber 知道車還活著。0 = 關閉（純 on-change）。
	 */
	int heartbeat_period = 300;
	/**
	 * Epoch millis 上一次「強制送完整 snapshot」的時間
	 */
	long lastFullPublishMs = 0L;

	/**
	 * Get preference int value
	 *
	 * @param key          preference key name
	 * @param defaultValue numeric default value
	 * @return preference int value
	 */
	public static int getPrefsInt(SharedPreferences prefs, String key, int defaultValue)
	{
		int result = defaultValue;
		
		try
		{
			result = Integer.valueOf(prefs.getString(key, String.valueOf(defaultValue)));
		}
		catch (Exception ex)
		{
			// log error message
			Log.e("Prefs", String.format("Preference '%s'(%d): %s", key, result, ex.toString()));
		}
		return result;
	}
	
	/**
	 * Working thread for cyclic publishing updates
	 */
	Thread updateThread = new Thread()
	{
		public void run()
		{
			Log.i("Thread", "started");
			try
			{
				while (!interrupted())
				{
					sleep(update_period * 1000);
					Log.i("Thread", "Publish data");
					performAction();
				}
			}
			catch (InterruptedException ignored)
			{
			}
			Log.i("Thread", "finished");
		}
	};
	
	@Override
	public void onCreate()
	{
		super.onCreate();
		
		// get preferences
		prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		prefs.registerOnSharedPreferenceChangeListener(this);
		// get all shared preference values
		onSharedPreferenceChanged(prefs, null);
		
		// set a proper client id if we have none
		if (mClientId == null || mClientId.trim().equals(""))
		{
			mClientId = MqttClient.generateClientId();
			// update preferences with generated client ID
			prefs.edit().putString(MQTT_CLIENTID, mClientId).apply();
		}
		updateThread.start();
	}
	
	@Override
	public void onDestroy()
	{
		// interrupt cyclic thread
		updateThread.interrupt();
		
		// forget about settings changes
		prefs.unregisterOnSharedPreferenceChangeListener(this);
		
		super.onDestroy();
	}
	
	/**
	 * Called when a shared preference is changed, added, or removed. This
	 * may be called even if a preference is set to its existing value.
	 * <p>
	 * <p>This callback will be run on your main thread.
	 *
	 * @param sharedPreferences The {@link SharedPreferences} that received
	 *                          the change.
	 * @param key               The key of the preference that was changed, added, or
	 */
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		if (key == null || MQTT_PREFIX.equals(key))
		{ mqtt_prefix = sharedPreferences.getString(MQTT_PREFIX, "AndrOBD/"); }
		
		if (key == null || UPDATE_PERIOD.equals(key))
		{ update_period = getPrefsInt(sharedPreferences, UPDATE_PERIOD, 30); }

		if (key == null || HEARTBEAT_PERIOD.equals(key))
		{ heartbeat_period = getPrefsInt(sharedPreferences, HEARTBEAT_PERIOD, 300); }
		
		if (key == null || MQTT_PROTOCOL.equals(key))
		{ brokerProtocol = sharedPreferences.getString(MQTT_PROTOCOL, getString(R.string.mqtt_prot_tcp)); }
		
		if (key == null || MQTT_HOSTNAME.equals(key))
		{ brokerHostName = sharedPreferences.getString(MQTT_HOSTNAME, "localhost"); }
		
		if (key == null || MQTT_PORT.equals(key))
		{ brokerPortNumber = getPrefsInt(sharedPreferences, MQTT_PORT, 1883); }
		
		if (key == null || MQTT_USERNAME.equals(key))
		{ mUsername = sharedPreferences.getString(MQTT_USERNAME, "guest"); }
		
		if (key == null || MQTT_PASSWORD.equals(key))
		{ mPassword = sharedPreferences.getString(MQTT_PASSWORD, "guest"); }

		if (key == null || MQTT_CLIENTID.equals(key))
		{ mClientId = sharedPreferences.getString(MQTT_CLIENTID, getString(R.string.app_name))
		                               .replaceAll("\\s+","-"); }

		if (key == null || MQTT_QOS.equals(key))
		{ mQos = getPrefsInt(sharedPreferences, MQTT_QOS, 0); }
		
		if (key == null || MQTT_RETAIN.equals(key))
		{ mRetain = sharedPreferences.getBoolean(MQTT_RETAIN, true); }
		
		if (key == null || ITEMS_SELECTED.equals(key))
		{
			mSelectedItems =
				(HashSet<String>) sharedPreferences.getStringSet(ITEMS_SELECTED, mSelectedItems);
		}
		
		if (key == null || ITEMS_KNOWN.equals(key))
		{
			mKnownItems =
				(HashSet<String>) sharedPreferences.getStringSet(ITEMS_KNOWN, mKnownItems);
		}
	}
	
	/**
	 * get own plugin info
	 */
	@Override
	public PluginInfo getPluginInfo()
	{
		return myInfo;
	}
	
	/**
	 * Perform intended action of the plugin
	 */
	@Override
	public void performAction()
	{
		MqttClient client;

		// Nothing to be sent - finished!
		if (valueMap.isEmpty()) { return; }

		// Heartbeat：太久沒 publish 任何資料 → 強制送一次完整快照，
		// 讓 MQTT subscriber 知道車還活著（heartbeat_period = 0 表示停用）
		final long now = System.currentTimeMillis();
		final boolean heartbeatDue = heartbeat_period > 0
			&& (now - lastFullPublishMs) >= (heartbeat_period * 1000L);

		// 先在鎖內計算這輪要 publish 的項目、不要在鎖內呼叫網路
		HashMap<String, String> toPublish = new HashMap<>();
		synchronized (valueMap)
		{
			for (Map.Entry<String, String> entry : valueMap.entrySet())
			{
				String key = entry.getKey();
				String value = entry.getValue();
				if (heartbeatDue)
				{
					// 超過 heartbeat 窗口 → 整批重送、不看是否變動
					toPublish.put(key, value);
				}
				else
				{
					String prev = lastPublishedMap.get(key);
					if (prev == null || !prev.equals(value))
					{
						toPublish.put(key, value);
					}
				}
			}
		}

		if (toPublish.isEmpty())
		{
			Log.d("MQTT", "No changes since last publish, skip");
			return;
		}

		// set URL
		final String BROKER_URL = brokerProtocol + brokerHostName + ":" + brokerPortNumber;

		try
		{
			Log.i("MQTT", "Connect: " + BROKER_URL + " (" + toPublish.size() + " changed item(s))");

			client = new MqttClient(BROKER_URL, mClientId, new MemoryPersistence());
			final MqttConnectOptions options = new MqttConnectOptions();
			if (mUsername != null && !mUsername.trim().equals(""))
			{
				options.setUserName(mUsername);
				options.setPassword(mPassword.toCharArray());
			}
			client.connect(options);

			for (Map.Entry<String, String> entry : toPublish.entrySet())
			{
				String topic = mqtt_prefix + entry.getKey();
				String value = entry.getValue();
				client.publish(topic, value.getBytes(), mQos, mRetain);
				Log.d("MQTT", "Published data. Topic: " + topic + " Message: " + value);
				// 只有 publish 成功才記錄、下一輪才會被 skip
				synchronized (lastPublishedMap)
				{
					lastPublishedMap.put(entry.getKey(), value);
				}
			}
			// 不論這輪是 diff 還是 heartbeat snapshot、都重設 heartbeat 計時器
			// → subscriber 在 heartbeat_period 內看到流量才當「車還活」
			lastFullPublishMs = System.currentTimeMillis();

			// disconnect client
			client.disconnect();
		}
		catch (MqttException e)
		{
			Log.e("MQTT", "Publish", e);
			// 連線失敗時別 update lastPublishedMap、下一輪會重試這些 key
		}

	}
	
	/**
	 * Handle configuration request.
	 * Perform plugin configuration
	 */
	@Override
	public void performConfigure()
	{
		Intent cfgIntent = new Intent(getApplicationContext(), SettingsActivity.class);
		cfgIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(cfgIntent);
	}
	
	/**
	 * Handle data list update.
	 *
	 * @param csvString CSV data string in format key;value
	 */
	@Override
	public void onDataListUpdate(String csvString)
	{
		// append unknown items to list of known items
		//noinspection SynchronizeOnNonFinalField
		synchronized (mKnownItems)
		{
			for (String csvLine : csvString.split("\n"))
			{
				String[] fields = csvLine.split(";");
				if (fields.length > 0)
				{
					mKnownItems.add(fields[0]);
				}
			}
			// store known items as preference
			prefs.edit().putStringSet(ITEMS_KNOWN, mKnownItems).apply();
		}
		// clear the map of received values
		synchronized (valueMap)
		{
			valueMap.clear();
		}
		// 連線重來 / 新一輪 — last published 記錄也要清、確保第一輪會 publish 一次完整 snapshot
		synchronized (lastPublishedMap)
		{
			lastPublishedMap.clear();
		}
	}
	
	/**
	 * Handle data update.
	 *
	 * @param key   Key of data change
	 * @param value New value of data change
	 */
	@Override
	public void onDataUpdate(String key, String value)
	{
		synchronized (valueMap)
		{
			// add value if it is to be published ...
			if (mSelectedItems.size() == 0 || mSelectedItems.contains(key))
			{
				valueMap.put(key, value);
			}
		}
	}
}
