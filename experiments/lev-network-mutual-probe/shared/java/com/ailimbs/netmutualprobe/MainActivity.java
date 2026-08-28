package com.ailimbs.netmutualprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000L); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("LEV Network Mutual Probe\n" + BuildConfig.ROLE);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("Package: " + getPackageName() + "\nPartner: " + BuildConfig.PARTNER_PACKAGE +
                "\nExternal topic: " + BuildConfig.TOPIC +
                "\n\nBoth apps can run the same ntfy HTTP stream. For the decisive test, keep mutual keepalive ON on both, enable external stream on A only, leave B external OFF, arm A's 90s server wake, then lock the screen.");
        info.setTextSize(14f);
        info.setPadding(0, pad / 2, 0, pad);
        box.addView(info);

        Button whitelist = button("1. REQUEST BATTERY WHITELIST");
        whitelist.setOnClickListener(v -> requestWhitelist());
        box.addView(whitelist);

        Button start = button("2. START MUTUAL KEEPALIVE");
        start.setOnClickListener(v -> { ProbeService.ensureRunning(this, "user_start"); refresh(); });
        box.addView(start);

        Button extOn = button("3. ENABLE EXTERNAL STREAM");
        extOn.setOnClickListener(v -> { ProbeService.setExternalEnabled(this, true); refresh(); });
        box.addView(extOn);

        Button extOff = button("4. DISABLE EXTERNAL STREAM");
        extOff.setOnClickListener(v -> { ProbeService.setExternalEnabled(this, false); refresh(); });
        box.addView(extOff);

        Button arm = button("5. ARM SERVER WAKE +90s");
        arm.setOnClickListener(v -> { ProbeService.armServerWake(this, 90); refresh(); });
        box.addView(arm);

        Button wake = button("SEND WAKE PING TO PARTNER NOW");
        wake.setOnClickListener(v -> { ProbeService.sendPartner(this, "PING", "manual_button"); refresh(); });
        box.addView(wake);

        Button stop = button("STOP THIS APP'S SERVICE");
        stop.setOnClickListener(v -> {
            ProbeStore.putBoolean(this, "external_enabled", false);
            stopService(new Intent(this, ProbeService.class));
            refresh();
        });
        box.addView(stop);

        Button refresh = button("REFRESH STATUS");
        refresh.setOnClickListener(v -> refresh());
        box.addView(refresh);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, pad, 0, pad);
        box.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return scroll;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void requestWhitelist() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable first) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Throwable second) { ProbeStore.putString(this, "last_error", "battery settings: " + second); }
        }
    }

    private void refresh() {
        if (status == null) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignored = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(getPackageName());
        long now = System.currentTimeMillis();
        long hb = ProbeStore.getLong(this, "last_heartbeat");
        long recv = ProbeStore.getLong(this, "last_received_ping");
        long ack = ProbeStore.getLong(this, "last_received_ack");
        long ext = ProbeStore.getLong(this, "last_external_packet");
        StringBuilder s = new StringBuilder();
        s.append("Battery optimization ignored: ").append(ignored).append('\n');
        s.append("External enabled: ").append(ProbeStore.getBoolean(this, "external_enabled")).append('\n');
        s.append("Network state: ").append(ProbeStore.getString(this, "network_state")).append('\n');
        s.append("External packet count: ").append(ProbeStore.getLong(this, "external_packet_count")).append('\n');
        s.append("Last external packet: ").append(ts(ext)).append(" age=").append(age(now,ext)).append('\n');
        s.append("Last external line: ").append(ProbeStore.getString(this, "last_external_line")).append('\n');
        s.append("Reconnect count: ").append(ProbeStore.getLong(this, "network_reconnect_count")).append('\n');
        s.append("Server wake: ").append(ProbeStore.getString(this, "server_wake_status")).append('\n');
        s.append("PID(last heartbeat): ").append(ProbeStore.getLong(this, "last_pid")).append('\n');
        s.append("Heartbeat count: ").append(ProbeStore.getLong(this, "heartbeat_count")).append('\n');
        s.append("Last heartbeat: ").append(ts(hb)).append(" age=").append(age(now,hb)).append('\n');
        s.append("Sent PING count: ").append(ProbeStore.getLong(this, "sent_ping_count")).append('\n');
        s.append("Received PING count: ").append(ProbeStore.getLong(this, "received_ping_count")).append('\n');
        s.append("Last partner PING: ").append(ts(recv)).append(" age=").append(age(now,recv)).append('\n');
        s.append("Received ACK count: ").append(ProbeStore.getLong(this, "received_ack_count")).append('\n');
        s.append("Last partner ACK: ").append(ts(ack)).append(" age=").append(age(now,ack)).append('\n');
        s.append("Receiver fires: ").append(ProbeStore.getLong(this, "receiver_count")).append('\n');
        s.append("Service creates/starts: ").append(ProbeStore.getLong(this, "service_create_count"))
                .append('/').append(ProbeStore.getLong(this, "service_start_count")).append('\n');
        s.append("Last start reason: ").append(ProbeStore.getString(this, "last_start_reason")).append('\n');
        s.append("Last receiver: ").append(ProbeStore.getString(this, "last_receiver_kind"))
                .append(" from ").append(ProbeStore.getString(this, "last_receiver_from")).append('\n');
        s.append("Last network error: ").append(ProbeStore.getString(this, "last_network_error")).append('\n');
        s.append("Last error: ").append(ProbeStore.getString(this, "last_error"));
        status.setText(s.toString());
    }

    private String ts(long value) {
        if (value <= 0) return "never";
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(value));
    }

    private String age(long now, long value) {
        if (value <= 0) return "n/a";
        return ((now - value) / 1000.0) + "s";
    }

    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(refresher); ui.post(refresher); }
    @Override protected void onPause() { ui.removeCallbacks(refresher); super.onPause(); }
}
