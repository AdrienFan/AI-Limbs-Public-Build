package com.ai.limbs.permission.server;

import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;
import java.util.List;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import moe.shizuku.server.IShizukuApplication;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.*;
import rikka.shizuku.server.api.IContentProviderUtils;

/**
 * Internal-only adaptation of Shizuku's Service. Host UID is the sole app principal.
 * Plugin-level policy remains in AI Limbs Host; no external app permission database.
 */
public final class PermissionServer extends Service<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> {
    private static final String TAG = "AIL.PermissionServer";
    private static final PrintStream STARTUP_LOG =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true);
    private static int hostUid;
    private static int userId;
    private static String packageName;
    private static String authority;
    private static String token;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // ContentProvider calls can block while Host is frozen. Keep them off the server's main
    // Looper so an explicit release/shutdown can always be processed independently.
    private final ExecutorService hostHandoffExecutor = Executors.newSingleThreadExecutor();
    private final Runnable handoffTask = () -> hostHandoffExecutor.execute(this::handoff);
    private final ResidentOwner residentOwner = new ResidentOwner(
        token, handler, () -> scheduleHostHandoff(0L), () -> {
            report("Resident ownership ended; stopping permission backend", null);
            System.exit(0);
        });
    private boolean acceptedOnce;
    private int missedHandoffs;

    public static void main(String[] args) {
        // app_process/RuntimeInit routes Android Log to logcat, not server.log.
        // Keep the redirected OS stderr explicitly so startup failures reach Log Center.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            report("Uncaught failure in " + thread.getName(), error);
            System.exit(1);
        });
        try {
            report("Java entry reached, uid=" + Os.getuid() + ", pid=" + Os.getpid(), null);
            runServer(args);
        } catch (Throwable error) {
            report("Permission server startup failed", error);
            System.exit(1);
        }
    }

    private static void report(String message, Throwable error) {
        // Do not print startup arguments or the launch token.
        PrintStream output = STARTUP_LOG;
        output.println(TAG + ": " + message);
        if (error != null) error.printStackTrace(output);
        // Do not close FileDescriptor.err; it belongs to the process.
        output.flush();
        if (error == null) Log.i(TAG, message);
        else Log.e(TAG, message, error);
    }

    private static void runServer(String[] args) throws Exception {
        if (Os.getuid() != 2000 && Os.getuid() != 0) throw new SecurityException("ADB or root activation required");
        if (args.length != 4) throw new IllegalArgumentException("Expected package, uid, user, token");
        packageName = args[0];
        hostUid = Integer.parseInt(args[1]);
        userId = Integer.parseInt(args[2]);
        token = args[3];
        if (!packageName.matches("[a-zA-Z0-9_.]+") || userId != hostUid / 100000) throw new SecurityException("Invalid Host identity");
        ApplicationInfo app = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0, userId);
        if (app == null || app.uid != hostUid) throw new SecurityException("Host package/UID mismatch");
        authority = packageName + ".privilege";
        report("Host identity verified; preparing Binder service", null);
        // This internal backend rejects the Rish transaction range below, so do not
        // initialize Rish JNI when constructing the shared Shizuku Service base.
        System.setProperty("ail.permission.rish.disabled", "true");
        Looper.prepareMainLooper();
        PermissionServer server = new PermissionServer();
        report("Binder service initialized; waiting for Host handoff", null);
        server.scheduleHostHandoff(0L);
        Looper.loop();
    }

    private void handoff() {
        if (residentOwner.ownsRuntime()) return;
        IContentProvider provider = null;
        try {
            if (!acceptedOnce) report("Requesting Host Provider", null);
            provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, authority);
            if (provider == null) throw new IllegalStateException("Host Provider unavailable");
            Bundle extras = new Bundle();
            extras.putString("token", token);
            extras.putBinder("binder", this);
            Bundle reply = IContentProviderUtils.callCompat(provider, null, authority, "attach", null, extras);
            // Core may claim ownership while this provider call is still in flight. An old
            // Host result cannot revoke the newly established independent lifetime.
            residentOwner.applyHostResult(() -> {
                if (reply == null || !reply.getBoolean("accepted")) {
                    report("Launch permission revoked or expired; exiting", null);
                    System.exit(0);
                }
                if (!acceptedOnce) report("Host accepted permission service", null);
                acceptedOnce = true;
                missedHandoffs = 0;
            });
        } catch (Throwable error) {
            residentOwner.applyHostResult(() -> {
                report("Host handoff failed", error);
                // Bounded lifecycle grace permits a Host process restart, never a different backend.
                if (++missedHandoffs >= (acceptedOnce ? 6 : 3)) System.exit(1);
            });
        } finally {
            if (provider != null) {
                try { ActivityManagerApis.removeContentProviderExternal(authority, null); }
                catch (Throwable error) { report("Provider release failed", error); }
            }
        }
        scheduleHostHandoff(10000L);
    }

    private void scheduleHostHandoff(long delayMillis) {
        handler.removeCallbacks(handoffTask);
        if (!residentOwner.ownsRuntime()) handler.postDelayed(handoffTask, delayMillis);
    }

    @Override public UserServiceManager onCreateUserServiceManager() {
        return new UserServiceManager() {
            @Override public String getUserServiceStartCmd(UserServiceRecord record, String key, String token,
                String packageName, String classname, String suffix, int uid, boolean use32, boolean debug) {
                throw new UnsupportedOperationException("External UserService is outside internal runtime v1");
            }
        };
    }
    @Override public ConfigManager onCreateConfigManager() {
        return new ConfigManager() {
            @Override public ConfigPackageEntry find(int uid) { return null; }
            @Override public void update(int uid, List<String> packages, int mask, int values) {
                throw new UnsupportedOperationException("Host owns plugin authorization");
            }
            @Override public void remove(int uid) {
                throw new UnsupportedOperationException("Host owns plugin authorization");
            }
        };
    }
    @Override public ClientManager<ConfigManager> onCreateClientManager() {
        return new ClientManager<>(getConfigManager());
    }
    @Override public boolean checkCallerManagerPermission(String function, int uid, int pid) { return uid == hostUid; }
    @Override public boolean checkCallerPermission(String function, int uid, int pid, ClientRecord client) { return uid == hostUid; }

    @Override public void attachApplication(IShizukuApplication application, Bundle args) {
        enforceManagerPermission("attachApplication");
        if (application == null || args == null || !packageName.equals(args.getString("shizuku:attach-package-name")))
            throw new SecurityException("Invalid Host client");
        ClientRecord client = getClientManager().addClient(hostUid, Binder.getCallingPid(), application, packageName, 13);
        if (client == null) throw new IllegalStateException("Host client died");
        client.allowed = true;
    }
    @Override public void showPermissionConfirmation(int code, ClientRecord client, int uid, int pid, int user) {
        throw new SecurityException("External authorization is not supported");
    }
    @Override public void exit() { enforceManagerPermission("exit"); System.exit(0); }
    @Override public void attachUserService(IBinder binder, Bundle args) {
        enforceManagerPermission("attachUserService");
        throw new UnsupportedOperationException("External UserService is not supported");
    }
    @Override public void dispatchPackageChanged(Intent intent) { enforceManagerPermission("dispatchPackageChanged"); }
    @Override public boolean isHidden(int uid) { enforceManagerPermission("isHidden"); return uid != hostUid; }
    @Override public void dispatchPermissionConfirmationResult(int uid, int pid, int code, Bundle data) {
        enforceManagerPermission("dispatchPermissionConfirmationResult");
        throw new UnsupportedOperationException("Host owns plugin authorization");
    }
    @Override public int getFlagsForUid(int uid, int mask) {
        enforceManagerPermission("getFlagsForUid");
        return (uid == hostUid ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED) & mask;
    }
    @Override public void updateFlagsForUid(int uid, int mask, int value) {
        enforceManagerPermission("updateFlagsForUid");
        throw new UnsupportedOperationException("Host owns plugin authorization");
    }
    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Do not permit the shared server UID exemption to broaden our exported app interface.
        if (code != IBinder.INTERFACE_TRANSACTION && Binder.getCallingUid() != hostUid)
            throw new SecurityException("Only the verified AI Limbs Host UID is accepted");
        if (code >= 30000 && code < 40000) throw new UnsupportedOperationException("Rish is not included");
        if (code == ResidentOwner.TRANSACTION) return residentOwner.transact(data, reply, flags);
        return super.onTransact(code, data, reply, flags);
    }
}
