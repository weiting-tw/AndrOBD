package com.fr3ts0n.ecu.gui.androbd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import java.util.logging.Logger;

public class BootReceiver extends BroadcastReceiver {
    private static final Logger log = Logger.getLogger("BootReceiver");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            log.info("Boot completed, checking if OBD service should start");
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            
            // We should only start if the user has enabled auto-start/auto-connect
            // For now, let's assume if they have a last device and "USE_LAST_SETTINGS" contains "LAST_DEV_ADDRESS"
            boolean useLast = prefs.getStringSet("USE_LAST_SETTINGS", java.util.Collections.emptySet())
                                  .contains("LAST_DEV_ADDRESS");
            
            if (useLast) {
                log.info("Starting ObdBackgroundService on boot");
                Intent serviceIntent = new Intent(context, ObdBackgroundService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
