package com.fr3ts0n.ecu.gui.androbd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.logging.Logger;

/**
 * Start the OBD background service after boot and after an app update
 * (unless the user explicitly stopped it).
 */
public class BootReceiver extends BroadcastReceiver
{
	private static final Logger log = Logger.getLogger("BootReceiver");

	@Override
	public void onReceive(Context context, Intent intent)
	{
		String action = intent.getAction();
		if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
			&& !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
			&& !"android.intent.action.QUICKBOOT_POWERON".equals(action))
		{
			return;
		}
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		boolean enabled = prefs.getBoolean(ObdBackgroundService.PREF_SERVICE_ENABLED, true);
		boolean configured = prefs.getString(ObdBackgroundService.PREF_DEVICE_ADDRESS, null) != null
			|| String.valueOf(CommService.MEDIUM.NETWORK.ordinal())
				.equals(prefs.getString(ObdBackgroundService.PREF_COMM_MEDIUM, "0"));
		if (enabled && configured)
		{
			log.info(action + " → starting ObdBackgroundService");
			ObdBackgroundService.start(context, ObdBackgroundService.ACTION_START);
		}
	}
}
