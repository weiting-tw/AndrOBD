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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvChangeListener;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service for OBD communication
 * Provides continuous monitoring and data collection even when app is in background
 */
public class ObdBackgroundService extends Service implements PvChangeListener {
    
    private static final String TAG = "ObdBackgroundService";
    private static final Logger log = Logger.getLogger(TAG);
    
    public static final String CHANNEL_ID = "obd_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int RECONNECT_DELAY = 5000; // 5 seconds
    
    // Service states
    public enum ServiceState {
        STOPPED, STARTING, RUNNING, STOPPING
    }
    
    private ServiceState currentState = ServiceState.STOPPED;
    private CommService commService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final List<ServiceStateListener> stateListeners = new ArrayList<>();
    
    private MqttClient mqttClient;
    private boolean mqttEnabled = false;
    private String mqttTopic = "androbd/data";
    private boolean autoReconnect = true;
    
    // Binder for local service binding
    public class LocalBinder extends Binder {
        public ObdBackgroundService getService() {
            return ObdBackgroundService.this;
        }
    }
    
    private final IBinder binder = new LocalBinder();
    
    // Interface for service state callbacks
    public interface ServiceStateListener {
        void onServiceStateChanged(ServiceState newState);
        void onDataReceived(String data);
        void onConnectionStateChanged(CommService.STATE connectionState);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupMqtt();
        ObdProt.PidPvs.addPvChangeListener(this, PvChangeEvent.PV_MODIFIED);
        log.info("ObdBackgroundService created");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log.info("ObdBackgroundService onStartCommand");
        if (currentState == ServiceState.STOPPED) {
            startForegroundService();
            initializeCommService();
            connectToLatestDevice();
        }
        // Return sticky to restart service if killed by system
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        autoReconnect = false;
        reconnectHandler.removeCallbacksAndMessages(null);
        stopCommService();
        ObdProt.PidPvs.removePvChangeListener(this);
        stopMqtt();
        currentState = ServiceState.STOPPED;
        notifyStateListeners();
        log.info("ObdBackgroundService destroyed");
        super.onDestroy();
    }
    
    private void startForegroundService() {
        Notification notification = createNotification("OBD Service Running", "Monitoring vehicle data...");
        startForeground(NOTIFICATION_ID, notification);
        currentState = ServiceState.RUNNING;
        notifyStateListeners();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OBD Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background OBD data monitoring");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification(String title, String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void initializeCommService() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        CommService.medium = CommService.MEDIUM.values()[getPrefsInt(prefs, "comm_medium", 0)];

        Handler serviceHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                handleCommServiceMessage(msg);
            }
        };
        
        switch (CommService.medium) {
            case BLUETOOTH:
                commService = new BtCommService(this, serviceHandler);
                break;
            case USB:
                commService = new UsbCommService(this, serviceHandler);
                break;
            case NETWORK:
                commService = new NetworkCommService(this, serviceHandler);
                break;
        }
        
        if (commService != null) {
            commService.start();
        }
    }
    
    private void stopCommService() {
        if (commService != null) {
            commService.stop();
            commService = null;
        }
    }
    
    private void handleCommServiceMessage(android.os.Message msg) {
        switch (msg.what) {
            case MainActivity.MESSAGE_STATE_CHANGE:
                CommService.STATE state = (CommService.STATE) msg.obj;
                notifyConnectionStateChanged(state);
                
                String notificationText = getNotificationTextForState(state);
                updateNotification("OBD Service", notificationText);

                if (state == CommService.STATE.OFFLINE && autoReconnect) {
                    scheduleReconnect();
                } else if (state == CommService.STATE.CONNECTED) {
                    reconnectHandler.removeCallbacksAndMessages(null);
                }
                break;
        }
    }
    
    private String getNotificationTextForState(CommService.STATE state) {
        switch (state) {
            case CONNECTING:
                return "Connecting to OBD device...";
            case CONNECTED:
                return "Connected - Monitoring vehicle data";
            case LISTEN:
                return "Waiting for connection...";
            default:
                return "OBD service running";
        }
    }
    
    private void updateNotification(String title, String message) {
        Notification notification = createNotification(title, message);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void connectToLatestDevice() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> restoreOptions = prefs.getStringSet("USE_LAST_SETTINGS", Collections.emptySet());
        boolean useLast = restoreOptions.contains("LAST_DEV_ADDRESS");
        
        if (!useLast) {
            log.info("Auto-connect disabled by settings");
            return;
        }

        String address = prefs.getString("LAST_DEV_ADDRESS", null);
        if (address != null) {
            log.info("Auto-connecting to last device: " + address);
            if (CommService.medium == CommService.MEDIUM.BLUETOOTH) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    boolean secure = prefs.getBoolean("bt_secure_connection", false);
                    connectToDevice(device, secure);
                }
            } else if (CommService.medium == CommService.MEDIUM.NETWORK) {
                String host = prefs.getString("device_address", "192.168.0.10");
                int port = getPrefsInt(prefs, "device_port", 35000);
                connectToDevice(host + ":" + port, true);
            }
        }
    }

    private void scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(() -> {
            log.info("Attempting auto-reconnect...");
            connectToLatestDevice();
        }, RECONNECT_DELAY);
    }
    
    public void connectToDevice(Object device, boolean secure) {
        if (commService != null) {
            if (commService instanceof NetworkCommService && device instanceof String) {
                String[] parts = ((String) device).split(":");
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                ((NetworkCommService) commService).connect(host, port);
            } else {
                commService.connect(device, secure);
            }
        }
    }
    
    public void disconnect() {
        if (commService != null) {
            commService.stop();
        }
    }
    
    public ServiceState getCurrentState() {
        return currentState;
    }
    
    public CommService.STATE getConnectionState() {
        return commService != null ? commService.getState() : CommService.STATE.NONE;
    }
    
    // Listener management
    public void addStateListener(ServiceStateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }
    
    public void removeStateListener(ServiceStateListener listener) {
        stateListeners.remove(listener);
    }
    
    private void notifyStateListeners() {
        mainHandler.post(() -> {
            for (ServiceStateListener listener : stateListeners) {
                listener.onServiceStateChanged(currentState);
            }
        });
    }
    
    private void notifyDataReceived(String data) {
        mainHandler.post(() -> {
            for (ServiceStateListener listener : stateListeners) {
                listener.onDataReceived(data);
            }
        });
    }
    
    private void notifyConnectionStateChanged(CommService.STATE state) {
        mainHandler.post(() -> {
            for (ServiceStateListener listener : stateListeners) {
                listener.onConnectionStateChanged(state);
            }
        });
    }

    private int getPrefsInt(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // MQTT Logic
    private void setupMqtt() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mqttEnabled = prefs.getBoolean("mqtt_enabled", false);
        if (!mqttEnabled) return;
        
        String broker = prefs.getString("mqtt_broker", "tcp://localhost:1883");
        mqttTopic = prefs.getString("mqtt_topic", "androbd/data");
        String user = prefs.getString("mqtt_user", "");
        String pass = prefs.getString("mqtt_pass", "");
        
        new Thread(() -> {
            try {
                mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new MemoryPersistence());
                MqttConnectOptions options = new MqttConnectOptions();
                if (!user.isEmpty()) {
                    options.setUserName(user);
                    options.setPassword(pass.toCharArray());
                }
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                mqttClient.connect(options);
                log.info("MQTT connected to " + broker);
            } catch (MqttException e) {
                log.log(Level.SEVERE, "MQTT Setup failed", e);
            }
        }).start();
    }

    private void stopMqtt() {
        if (mqttClient != null) {
            try {
                mqttClient.disconnect();
                mqttClient.close();
            } catch (MqttException e) {
                log.log(Level.WARNING, "MQTT stop failed", e);
            }
        }
    }

    private void publishMqtt(String data) {
        if (mqttEnabled && mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(data.getBytes());
                message.setQos(0);
                mqttClient.publish(mqttTopic, message);
            } catch (MqttException e) {
                log.log(Level.WARNING, "MQTT publish failed", e);
            }
        }
    }

    @Override
    public void pvChanged(PvChangeEvent event) {
        if (event.getType() == PvChangeEvent.PV_MODIFIED) {
            EcuDataPv pv = (EcuDataPv) event.getSource();
            String mnemonic = (String) pv.get(EcuDataPv.FID_MNEMONIC);
            Object value = pv.get(EcuDataPv.FIELDS[EcuDataPv.FID_VALUE]);
            String unit = (String) pv.get(EcuDataPv.FIELDS[EcuDataPv.FID_UNITS]);
            
            String payload = String.format("{\"mnemonic\":\"%s\", \"value\":\"%s\", \"unit\":\"%s\"}", 
                                          mnemonic, value, unit);
            publishMqtt(payload);
            notifyDataReceived(payload);
        }
    }
}
