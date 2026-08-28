package com.ailimbs.levprobe.v1;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    public static final String PREFS = "lev_probe";
    private static final long PROBE_DELAY_MS = 60_000L;
    private static final int ALARM_REQUEST_CODE = 6101;
    private TextView statusView;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        maybeRequestNotificationPermission();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) refreshStatus();
    }

    private ScrollView buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("LEV Exact Alarm Probe\n\nGoal: lock the screen after arming. The exact alarm fires after 60 seconds. Leave the screen off for at least 90 seconds, then unlock and refresh.");
        title.setTextSize(18f);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(button("1. GRANT EXACT ALARM ACCESS", v -> openExactAlarmSettings()));
        root.addView(button("2. ARM 60-SECOND PROBE", v -> armProbe()));
        root.addView(button("REFRESH STATUS", v -> refreshStatus()));
        root.addView(button("STOP + RESET", v -> stopAndReset()));

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        statusView.setPadding(0, pad, 0, pad);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }
    private Button button(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setAllCaps(false);
        return button;
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private boolean canScheduleExactAlarms() {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }
    private void armProbe() {
        if (!canScheduleExactAlarms()) {
            openExactAlarmSettings();
            return;
        }
        cancelAlarm();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        long triggerElapsed = nowElapsed + PROBE_DELAY_MS;
        prefs.edit().clear()
                .putLong("armed_wall", nowWall)
                .putLong("armed_elapsed", nowElapsed)
                .putLong("planned_alarm_wall", nowWall + PROBE_DELAY_MS)
                .putLong("planned_alarm_elapsed", triggerElapsed)
                .commit();

        Intent serviceIntent = new Intent(this, ProbeService.class).setAction(ProbeService.ACTION_ARM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerElapsed,
                alarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT));
        refreshStatus();
    }
    private PendingIntent alarmPendingIntent(int updateFlag) {
        Intent alarmIntent = new Intent(this, AlarmReceiver.class)
                .setAction("com.ailimbs.levprobe.v1.EXACT_ALARM");
        return PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                alarmIntent,
                PendingIntent.FLAG_IMMUTABLE | updateFlag);
    }

    private void cancelAlarm() {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        PendingIntent pending = PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                new Intent(this, AlarmReceiver.class).setAction("com.ailimbs.levprobe.v1.EXACT_ALARM"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            alarmManager.cancel(pending);
            pending.cancel();
        }
    }

    private void stopAndReset() {
        cancelAlarm();
        stopService(new Intent(this, ProbeService.class));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
        refreshStatus();
    }
    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long plannedWall = p.getLong("planned_alarm_wall", 0L);
        long plannedElapsed = p.getLong("planned_alarm_elapsed", 0L);
        long firedWall = p.getLong("alarm_fired_wall", 0L);
        long firedElapsed = p.getLong("alarm_fired_elapsed", 0L);
        long serviceWakeElapsed = p.getLong("alarm_service_wake_elapsed", 0L);
        long lastHeartbeatWall = p.getLong("last_heartbeat_wall", 0L);

        StringBuilder out = new StringBuilder();
        out.append("Package: ").append(getPackageName()).append('\n');
        out.append("Exact alarm access: ").append(canScheduleExactAlarms()).append("\n\n");
        out.append("Armed: ").append(formatWall(p.getLong("armed_wall", 0L))).append('\n');
        out.append("Planned alarm: ").append(formatWall(plannedWall)).append('\n');
        out.append("Receiver fired: ").append(formatWall(firedWall)).append('\n');
        if (plannedElapsed > 0L && firedElapsed > 0L) {
            out.append("Alarm delivery delta: ")
                    .append(firedElapsed - plannedElapsed).append(" ms\n");
        }
        out.append("Receiver count: ").append(p.getInt("receiver_count", 0)).append('\n');
        out.append("Receiver requested FGS: ")
                .append(p.getBoolean("receiver_started_service", false)).append('\n');
        String receiverError = p.getString("receiver_error", "");
        if (receiverError != null && !receiverError.isEmpty()) {
            out.append("Receiver error: ").append(receiverError).append('\n');
        }
        out.append("Service alarm wake: ").append(formatWall(p.getLong("alarm_service_wake_wall", 0L))).append('\n');
        if (firedElapsed > 0L && serviceWakeElapsed > 0L) {
            out.append("Receiver -> service delta: ")
                    .append(serviceWakeElapsed - firedElapsed).append(" ms\n");
        }
        out.append("Service create count: ").append(p.getInt("service_create_count", 0)).append('\n');
        out.append("Service start count: ").append(p.getInt("service_start_count", 0)).append('\n');
        out.append("Heartbeat count: ").append(p.getInt("heartbeat_count", 0)).append('\n');
        out.append("Last heartbeat: ").append(formatWall(lastHeartbeatWall)).append('\n');
        long lastHeartbeatElapsed = p.getLong("last_heartbeat_elapsed", 0L);
        if (lastHeartbeatElapsed > 0L) {
            out.append("Heartbeat age now: ")
                    .append(SystemClock.elapsedRealtime() - lastHeartbeatElapsed)
                    .append(" ms\n");
        }
        statusView.setText(out.toString());
    }

    private String formatWall(long wall) {
        return wall <= 0L ? "--" : clock.format(new Date(wall));
    }
}