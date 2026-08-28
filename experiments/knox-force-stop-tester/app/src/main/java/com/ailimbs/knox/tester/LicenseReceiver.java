package com.ailimbs.knox.tester;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class LicenseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        StringBuilder out = new StringBuilder("action=").append(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                out.append("\n").append(key).append("=").append(String.valueOf(value));
            }
        }
        context.getSharedPreferences("state", Context.MODE_PRIVATE)
                .edit().putString("last_license_result", out.toString()).apply();
        Toast.makeText(context, "Knox license result received", Toast.LENGTH_LONG).show();
    }
}
