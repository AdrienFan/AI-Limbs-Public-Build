package com.ailimbs.levprobe.v1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ProbeService extends Service {
    public static final String ACTION_ARM = "com.ailimbs.levprobe.ARM";
    public static final String ACTION_ALARM_WAKE = "com.ailimbs.levprobe.ALARM_WAKE";
    private static final String TAG = "LEVProbe";
    private static final String CHANNEL_ID = "lev_probe";
    private static final int NOTIFICATION_ID = 4101;
    private ScheduledExecutorService heartbeatExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        int createCount = prefs.getInt("service_create_count", 0) + 1;
        prefs.edit()
                .putInt("service_create_count", createCount)
                .putLong("service_create_wall", System.currentTimeMillis())
                .putLong("service_create_elapsed", SystemClock.elapsedRealtime())
                .commit();
        Log.i(TAG, "ProbeService created count=" + createCount);

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(this::recordHeartbeat, 0, 2, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        int startCount = prefs.getInt("service_start_count", 0) + 1;
        SharedPreferences.Editor edit = prefs.edit()
                .putInt("service_start_count", startCount)
                .putLong("service_start_wall", System.currentTimeMillis())
                .putLong("service_start_elapsed", SystemClock.elapsedRealtime());
        if (intent != null && ACTION_ALARM_WAKE.equals(intent.getAction())) {
            edit.putLong("alarm_service_wake_wall", System.currentTimeMillis())
                    .putLong("alarm_service_wake_elapsed", SystemClock.elapsedRealtime());
            Log.i(TAG, "ProbeService received exact-alarm wake action");
        }
        edit.commit();
        return START_STICKY;
    }
    private void recordHeartbeat() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        int count = prefs.getInt("heartbeat_count", 0) + 1;
        prefs.edit()
                .putInt("heartbeat_count", count)
                .putLong("last_heartbeat_wall", System.currentTimeMillis())
                .putLong("last_heartbeat_elapsed", SystemClock.elapsedRealtime())
                .commit();
        if (count % 5 == 0) {
            Log.i(TAG, "Heartbeat count=" + count);
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "LEV probe heartbeat",
                NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("LEV Exact Alarm Probe")
                .setContentText("Heartbeat active; waiting for exact-alarm wake test")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        Log.i(TAG, "ProbeService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}