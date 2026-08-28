package com.ailimbs.netmutualprobe;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProbeService extends Service {
    static final String ACTION_WAKE = "com.ailimbs.netmutualprobe.ACTION_WAKE";
    static final String RECEIVER_CLASS = "com.ailimbs.netmutualprobe.PartnerWakeReceiver";
    private static final String CHANNEL = "net_mutual_probe_fgs";
    private static final int NOTIFICATION_ID = 6207;
    private static final String TAG = "NetMutualProbe";
    private ScheduledExecutorService scheduler;
    private long localTick;
    private volatile Thread networkThread;
    private volatile HttpURLConnection networkConnection;

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

    public static void setExternalEnabled(Context context, boolean enabled) {
        ProbeStore.putBoolean(context, "external_enabled", enabled);
        ProbeStore.putString(context, "last_external_toggle", enabled ? "enabled" : "disabled");
        ensureRunning(context, enabled ? "external_enable" : "external_disable");
    }

    public static void armServerWake(Context context, int seconds) {
        ProbeStore.putString(context, "server_wake_status", "arming " + seconds + "s...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://ntfy.sh/" + BuildConfig.TOPIC);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Delay", seconds + "s");
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                c.setDoOutput(true);
                byte[] body = ("LEV server wake for " + BuildConfig.ROLE + " at " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
                int code = c.getResponseCode();
                ProbeStore.putLong(context, "server_wake_armed_at", System.currentTimeMillis());
                ProbeStore.putString(context, "server_wake_status", "armed HTTP " + code + " for +" + seconds + "s");
                Log.w(TAG, BuildConfig.ROLE + " SERVER WAKE armed +" + seconds + "s HTTP=" + code);
            } catch (Throwable t) {
                ProbeStore.putString(context, "server_wake_status", "arm failed: " + t);
                ProbeStore.putString(context, "last_error", "armServerWake: " + t);
                Log.e(TAG, BuildConfig.ROLE + " server wake arm failed", t);
            } finally {
                if (c != null) c.disconnect();
            }
        }, "server-wake-arm").start();
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
            } else ProbeStore.inc(context, "sent_ack_count");
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
                .setContentTitle(BuildConfig.ROLE + " network guardian")
                .setContentText("2s mutual ping + optional ntfy external stream")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true).build();
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
        syncExternalState();
        localTick++;
        if (localTick % 5 == 0) Log.i(TAG, BuildConfig.ROLE + " heartbeat=" + count + " external=" + ProbeStore.getBoolean(this,"external_enabled"));
    }

    private void syncExternalState() {
        boolean enabled = ProbeStore.getBoolean(this, "external_enabled");
        Thread t = networkThread;
        if (enabled && (t == null || !t.isAlive())) startNetworkThread();
        if (!enabled && t != null && t.isAlive()) stopNetworkConnection();
    }

    private synchronized void startNetworkThread() {
        if (networkThread != null && networkThread.isAlive()) return;
        networkThread = new Thread(this::networkLoop, "ntfy-stream");
        networkThread.start();
    }

    private void networkLoop() {
        while (ProbeStore.getBoolean(this, "external_enabled")) {
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://ntfy.sh/" + BuildConfig.TOPIC + "/json");
                c = (HttpURLConnection) u.openConnection();
                networkConnection = c;
                c.setConnectTimeout(15000);
                c.setReadTimeout(0);
                c.setRequestProperty("Accept", "application/x-ndjson");
                c.setRequestProperty("User-Agent", "AI-Limbs-LEV-Network-Probe/0.2");
                int code = c.getResponseCode();
                ProbeStore.putLong(this, "last_network_connect", System.currentTimeMillis());
                ProbeStore.putString(this, "network_state", "connected HTTP " + code);
                Log.w(TAG, BuildConfig.ROLE + " EXTERNAL CONNECT HTTP=" + code + " topic=" + BuildConfig.TOPIC);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (ProbeStore.getBoolean(this, "external_enabled") && (line = br.readLine()) != null) {
                        long now = System.currentTimeMillis();
                        long n = ProbeStore.inc(this, "external_packet_count");
                        ProbeStore.putLong(this, "last_external_packet", now);
                        ProbeStore.putString(this, "last_external_line", line.length() > 220 ? line.substring(0,220) : line);
                        Log.w(TAG, BuildConfig.ROLE + " EXTERNAL PACKET #" + n + " " + (line.length()>100?line.substring(0,100):line));
                        sendPartner(this, "PING", "external_packet_" + n);
                    }
                }
            } catch (Throwable t) {
                if (ProbeStore.getBoolean(this, "external_enabled")) {
                    ProbeStore.inc(this, "network_reconnect_count");
                    ProbeStore.putString(this, "network_state", "disconnected: " + t.getClass().getSimpleName());
                    ProbeStore.putString(this, "last_network_error", String.valueOf(t));
                    Log.w(TAG, BuildConfig.ROLE + " external stream error " + t);
                }
            } finally {
                if (c != null) c.disconnect();
                networkConnection = null;
            }
            if (!ProbeStore.getBoolean(this, "external_enabled")) break;
            try { Thread.sleep(5000L); } catch (InterruptedException e) { break; }
        }
        ProbeStore.putString(this, "network_state", "stopped");
    }

    private void stopNetworkConnection() {
        HttpURLConnection c = networkConnection;
        if (c != null) c.disconnect();
        Thread t = networkThread;
        if (t != null) t.interrupt();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = intent == null ? "sticky_restart" : intent.getStringExtra("reason");
        ProbeStore.inc(this, "service_start_count");
        ProbeStore.putString(this, "last_start_reason", String.valueOf(reason));
        ProbeStore.putLong(this, "last_service_start", System.currentTimeMillis());
        syncExternalState();
        Log.i(TAG, BuildConfig.ROLE + " onStartCommand reason=" + reason);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        stopNetworkConnection();
        ProbeStore.putLong(this, "last_service_destroy", System.currentTimeMillis());
        Log.w(TAG, BuildConfig.ROLE + " service destroyed");
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "LEV network mutual probe", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
