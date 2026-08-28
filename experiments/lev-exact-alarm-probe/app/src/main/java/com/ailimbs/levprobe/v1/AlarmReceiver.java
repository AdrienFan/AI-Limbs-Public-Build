package com.ailimbs.levprobe.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "LEVProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt("receiver_count", 0) + 1;
        prefs.edit()
                .putLong("alarm_fired_wall", wall)
                .putLong("alarm_fired_elapsed", elapsed)
                .putInt("receiver_count", count)
                .putString("receiver_error", "")
                .commit();
        Log.i(TAG, "Exact alarm receiver fired count=" + count + " elapsed=" + elapsed);

        Intent serviceIntent = new Intent(context, ProbeService.class)
                .setAction(ProbeService.ACTION_ALARM_WAKE);        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            prefs.edit().putBoolean("receiver_started_service", true).commit();
            Log.i(TAG, "Receiver requested foreground service restart");
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            prefs.edit()
                    .putBoolean("receiver_started_service", false)
                    .putString("receiver_error", error)
                    .commit();
            Log.e(TAG, "Receiver failed to restart foreground service", t);
        }
    }
}