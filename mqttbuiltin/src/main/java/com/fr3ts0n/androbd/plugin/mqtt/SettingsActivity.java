package com.fr3ts0n.androbd.plugin.mqtt;

import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MQTT (Home Assistant) settings
 */
public class SettingsActivity extends PreferenceActivity
{
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.mqtt_settings);
		initItemSelection();
	}

	/**
	 * Initialize selection of data items to be published
	 * from list of data items seen so far
	 */
	void initItemSelection()
	{
		Set<String> known = PreferenceManager.getDefaultSharedPreferences(this)
			.getStringSet(MqttPublisher.ITEMS_KNOWN, new HashSet<>());
		String[] items = known.toArray(new String[0]);
		Arrays.sort(items);

		MultiSelectListPreference pref =
			(MultiSelectListPreference) findPreference(MqttPublisher.ITEMS_SELECTED);
		pref.setEntries(items);
		pref.setEntryValues(items);
	}
}
