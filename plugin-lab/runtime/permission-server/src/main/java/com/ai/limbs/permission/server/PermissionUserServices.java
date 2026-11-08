package com.ai.limbs.permission.server;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import moe.shizuku.server.IShizukuServiceConnection;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.UserServiceManager;
import rikka.shizuku.server.UserServiceRecord;

/** UserService records are also tied to their real child PID, so revocation can stop a
 * service even when app code ignores the standard destroy transaction. */
final class PermissionUserServices extends UserServiceManager {
    static final int BOOTSTRAP_TRANSACTION = 0x414951;
    static final String BOOTSTRAP_DESCRIPTOR = "ai_limbs.permission.user_service.v1";
    private final PermissionServer server;
    PermissionUserServices(PermissionServer server) { this.server = server; }

    @Override public PackageInfo ensureCallingPackageForUserService(String packageName, int appId, int userId) {
        PackageInfo info = super.ensureCallingPackageForUserService(packageName, appId, userId);
        if (!server.isAuthorizedClient(info.applicationInfo.uid)) throw new SecurityException("UserService authorization was revoked");
        return info;
    }
    @Override public int addUserService(IShizukuServiceConnection connection, Bundle options, int apiVersion) {
        if (options == null) throw new IllegalArgumentException("Missing UserService options");
        if (options.getBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, false))
            throw new UnsupportedOperationException("UserService JDWP debug startup is not implemented");
        return super.addUserService(connection, options, apiVersion);
    }

    private static final class Owned {
        final String packageName;
        final int uid;
        int pid = -1;
        String startTime;
        IBinder lifetime;
        Owned(String packageName, int uid) { this.packageName = packageName; this.uid = uid; }
    }
    private final Map<UserServiceRecord, Owned> owned = new HashMap<>();

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Override public String getUserServiceStartCmd(UserServiceRecord record, String key, String token,
            String packageName, String classname, String suffix, int uid, boolean use32, boolean debug) {
        if (!server.isAuthorizedClient(uid)) throw new SecurityException("UserService authorization was revoked");
        if (debug) throw new UnsupportedOperationException("UserService JDWP debug startup is not implemented");
        String binary = use32 ? "/system/bin/app_process32" : "/system/bin/app_process";
        if (!new File(binary).canExecute()) throw new IllegalStateException("Requested app_process ABI is unavailable");
        String name = packageName + ":" + (suffix == null ? "user_service" : suffix);
        String command = "exec " + binary + " /system/bin " + quote("--nice-name=" + name) + " "
                + PermissionUserServiceMain.class.getName() + " "
                + quote("--package=" + packageName) + " " + quote("--class=" + classname) + " "
                + quote("--uid=" + uid) + " " + quote("--token=" + token) + " " + quote("--debug-name=" + name);
        // Establish a detached session before app code is loaded. No application values are shell code.
        return "trap '' HUP; CLASSPATH=" + quote(System.getProperty("java.class.path"))
                + " /system/bin/setsid /system/bin/sh -c " + quote(command) + " </dev/null >/dev/null 2>&1 &\n";
    }

    @Override public synchronized void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo info) {
        owned.put(record, new Owned(info.packageName, info.applicationInfo.uid));
    }

    private UserServiceRecord findToken(String token) {
        for (UserServiceRecord candidate : owned.keySet()) if (candidate.token.equals(token)) return candidate;
        throw new SecurityException("UserService launch token is unknown or revoked");
    }

    synchronized void claim(String token, IBinder lifetime) throws RemoteException {
        UserServiceRecord record = findToken(token);
        Owned value = owned.get(record);
        int pid = Binder.getCallingPid();
        if (value.pid != -1 || pid == Os.getpid() || lifetime == null || !server.isAuthorizedClient(value.uid))
            throw new SecurityException("Invalid or revoked UserService launch");
        value.startTime = processStartTime(pid);
        value.pid = pid;
        value.lifetime = lifetime;
        lifetime.linkToDeath(record::removeSelf, 0);
        if (!lifetime.isBinderAlive()) { record.removeSelf(); throw new RemoteException("UserService bootstrap died"); }
    }

    @Override public synchronized void attachUserService(IBinder binder, Bundle options) {
        String token = Objects.requireNonNull(options.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN));
        UserServiceRecord record = findToken(token);
        Owned value = owned.get(record);
        if (value.pid != Binder.getCallingPid() || value.lifetime == null || record.service != null
                || !server.isAuthorizedClient(value.uid)) throw new SecurityException("UserService bootstrap is not authorized");
        super.attachUserService(binder, options);
    }

    private static String processStartTime(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), java.nio.charset.StandardCharsets.UTF_8);
            // comm can contain spaces and parentheses. Field 22 is index 19 after its closing ')'.
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
            return fields[19];
        } catch (Exception error) { throw new IllegalStateException("Cannot verify UserService process", error); }
    }

    @Override public synchronized void onUserServiceRecordRemoved(UserServiceRecord record) {
        Owned value = owned.remove(record);
        if (value == null || value.pid <= 0) return;
        try {
            File stat = new File("/proc/" + value.pid + "/stat");
            if (stat.exists() && value.startTime.equals(processStartTime(value.pid))) Os.kill(value.pid, OsConstants.SIGKILL);
        } catch (Exception error) { Log.w("AIL.UserServices", "UserService process cleanup failed", error); }
    }

    synchronized void shutdown() {
        for (UserServiceRecord record : new ArrayList<>(owned.keySet())) record.removeSelf();
    }
}
