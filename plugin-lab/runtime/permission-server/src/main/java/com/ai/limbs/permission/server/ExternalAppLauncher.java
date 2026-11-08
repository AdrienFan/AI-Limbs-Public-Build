package com.ai.limbs.permission.server;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.ServiceManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit user-requested app launch; no periodic discovery or alternate backend. */
final class ExternalAppLauncher {
    private ExternalAppLauncher() {}

    @SuppressWarnings("NewApi")
    static void launch(String packageName, int uid, int userId) throws Exception {
        if (!packageName.matches("[a-zA-Z0-9_.]+") || uid / 100000 != userId)
            throw new SecurityException("Invalid app launch identity");
        IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        // Launcher filters need not contain CATEGORY_DEFAULT. Resolve with flags=0, then
        // explicitly launch that component, as Android's getLaunchIntentForPackage does.
        ParceledListSlice<ResolveInfo> slice = Build.VERSION.SDK_INT >= 33
                ? pm.queryIntentActivities(query, null, 0L, userId)
                : pm.queryIntentActivities(query, null, 0, userId);
        List<ResolveInfo> matches = slice == null ? null : slice.getList();
        if (matches == null || matches.isEmpty())
            throw new IllegalStateException("该应用没有可启动的主界面，无法建立手动连接");
        ActivityInfo activity = matches.get(0).activityInfo;
        if (activity == null || !activity.exported || !activity.enabled
                || !packageName.equals(activity.packageName) || activity.applicationInfo == null
                || activity.applicationInfo.uid != uid || !activity.applicationInfo.enabled)
            throw new SecurityException("App launch component identity changed");
        ComponentName component = new ComponentName(activity.packageName, activity.name);

        // The platform's -W waits for launch completion instead of assuming that a successful
        // asynchronous start already made the protected Provider available. Arguments never
        // enter a shell interpreter; cmd executes with this ADB/root backend's own UID.
        Process process = new ProcessBuilder("/system/bin/cmd", "activity", "start-activity", "-W",
                "--user", Integer.toString(userId), "-n", component.flattenToString())
                .redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS))
                throw new IllegalStateException("启动目标应用超时，请检查应用是否能正常打开");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 16384)
                        throw new IllegalStateException("目标应用启动结果过大，连接已停止");
                    bytes.write(buffer, 0, count);
                }
            }
            AppLaunchResult.requireComplete(packageName, process.exitValue(),
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            process.destroyForcibly();
        }
    }
}
