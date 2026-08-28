package com.ailimbs.mutualprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProbeService extends Service {
    static final String ACTION_WAKE = "com.ailimbs.mutualprobe.ACTION_WAKE";
    static final String RECEIVER_CLASS = "com.ailimbs.mutualprobe.PartnerWakeReceiver";
    private static final String CHANNEL = "mutual_probe_fgs";
    private static final int NOTIFICATION_ID = 6107;
    private static final String TAG = "MutualProbe";
    private ScheduledExecutorService scheduler;
    private long localTick;

    public static void ensureRunning(Context context, String reason) {
        Intent i = new Intent(context, ProbeService.class).putExtra("reason", reason);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
            ProbeStore.putString(context, "last_start_request", reason + " @" + System.currentTimeMillis());
        } catch (Throwable t) {
            ProbeStore.putString(context, "last_error", "startSelf: " + t);
            Log.e(TAG, BuildConfig.ROLE + " start self failed", t);
        }
    }

    static void sendPartner(Context context, String kind, String reason) {
        Intent i = new Intent(ACTION_WAKE);
        i.setComponent(new ComponentName(BuildConfig.PARTNER_PACKAGE, RECEIVER_CLASS));
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra("kind", kind);
        i.putExtra("reason", reason);
        i.putExtra("sender_package", context.getPackageName());
        i.putExtra("sender_role", BuildConfig.ROLE);
        i.putExtra("sent_at", System.currentTimeMillis());
        try {
            context.sendBroadcast(i);
            if ("PING".equals(kind)) {
                ProbeStore.inc(context, "sent_ping_count");
                ProbeStore.putLong(context, "last_sent_ping", System.currentTimeMillis());
            } else {
                ProbeStore.inc(context, "sent_ack_count");
            }
        } catch (Throwable t) {
            ProbeStore.putString(context, "last_error", "sendPartner: " + t);
            Log.e(TAG, BuildConfig.ROLE + " partner broadcast failed", t);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        ProbeStore.inc(this, "service_create_count");
        ProbeStore.putLong(this, "last_service_create", System.currentTimeMillis());
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(BuildConfig.ROLE + " mutual keepalive")
                .setContentText("2s heartbeat + cross-package wake ping")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, n);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::tick, 0, 2, TimeUnit.SECONDS);
        Log.i(TAG, BuildConfig.ROLE + " service created pid=" + android.os.Process.myPid());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long count = ProbeStore.inc(this, "heartbeat_count");
        ProbeStore.putLong(this, "last_heartbeat", now);
        ProbeStore.putLong(this, "last_pid", android.os.Process.myPid());
        sendPartner(this, "PING", "periodic_tick_" + count);
        localTick++;
        if (localTick % 5 == 0) {
            Log.i(TAG, BuildConfig.ROLE + " heartbeat=" + count + " partner=" + BuildConfig.PARTNER_PACKAGE);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = intent == null ? "sticky_restart" : intent.getStringExtra("reason");
        ProbeStore.inc(this, "service_start_count");
        ProbeStore.putString(this, "last_start_reason", String.valueOf(reason));
        ProbeStore.putLong(this, "last_service_start", System.currentTimeMillis());
        Log.i(TAG, BuildConfig.ROLE + " onStartCommand reason=" + reason);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        ProbeStore.putLong(this, "last_service_destroy", System.currentTimeMillis());
        Log.w(TAG, BuildConfig.ROLE + " service destroyed");
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "LEV mutual probe", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
