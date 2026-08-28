package com.ailimbs.netmutualprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class PartnerWakeReceiver extends BroadcastReceiver {
    private static final String TAG = "NetMutualProbe";

    @Override public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        String kind = intent == null ? "" : intent.getStringExtra("kind");
        String senderRole = intent == null ? "" : intent.getStringExtra("sender_role");
        String reason = intent == null ? "" : intent.getStringExtra("reason");
        ProbeStore.inc(context, "receiver_count");
        ProbeStore.putLong(context, "last_receiver_fire", now);
        ProbeStore.putString(context, "last_receiver_kind", String.valueOf(kind));
        ProbeStore.putString(context, "last_receiver_from", String.valueOf(senderRole));

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getPackageName() + ":partner_wake");
            wl.setReferenceCounted(false);
            wl.acquire(10_000L);
        } catch (Throwable t) {
            ProbeStore.putString(context, "last_error", "wakelock: " + t);
        }

        if ("ACK".equals(kind)) {
            ProbeStore.inc(context, "received_ack_count");
            ProbeStore.putLong(context, "last_received_ack", now);
            Log.i(TAG, BuildConfig.ROLE + " RECEIVER ACK from=" + senderRole);
            return;
        }

        ProbeStore.inc(context, "received_ping_count");
        ProbeStore.putLong(context, "last_received_ping", now);
        Log.w(TAG, BuildConfig.ROLE + " RECEIVER PING from=" + senderRole + " reason=" + reason + " pid=" + android.os.Process.myPid());
        ProbeService.ensureRunning(context, "partner_ping_from_" + senderRole);
        ProbeService.sendPartner(context, "ACK", "ack_partner_ping");
    }
}
