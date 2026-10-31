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

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;

import com.fr3ts0n.ecu.prot.obd.ElmProt;

/**
 * Connection settings. Changes are picked up live by {@link ObdBackgroundService}.
 */
public class SettingsActivity extends PreferenceActivity
{
	@SuppressWarnings("deprecation")
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.settings);

		// preferred OBD protocol
		ElmProt.PROT[] protocols = ElmProt.PROT.values();
		CharSequence[] protTitles = new CharSequence[protocols.length];
		CharSequence[] protKeys = new CharSequence[protocols.length];
		for (int i = 0; i < protocols.length; i++)
		{
			protTitles[i] = protocols[i].toString();
			protKeys[i] = String.valueOf(protocols[i].ordinal());
		}
		setupList(ObdBackgroundService.PREF_PROTOCOL, protTitles, protKeys);

		// ELM adaptive timing
		ElmProt.AdaptTimingMode[] modes = ElmProt.AdaptTimingMode.values();
		CharSequence[] modeNames = new CharSequence[modes.length];
		for (int i = 0; i < modes.length; i++)
		{
			modeNames[i] = modes[i].toString();
		}
		setupList(ObdBackgroundService.PREF_ADAPTIVE_TIMING, modeNames, modeNames);
	}

	@SuppressWarnings("deprecation")
	private void setupList(String key, CharSequence[] titles, CharSequence[] values)
	{
		ListPreference pref = (ListPreference) findPreference(key);
		pref.setEntries(titles);
		pref.setEntryValues(values);
		pref.setSummary("%s");
	}
}
