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
    private static int hostUid;
    private static int userId;
    private static String packageName;
    private static String authority;
    private static String token;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean acceptedOnce;
    private int missedHandoffs;

    public static void main(String[] args) throws Exception {
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
        // This internal backend rejects the Rish transaction range below, so do not
        // initialize Rish JNI when constructing the shared Shizuku Service base.
        System.setProperty("ail.permission.rish.disabled", "true");
        Looper.prepareMainLooper();
        PermissionServer server = new PermissionServer();
        server.handler.post(server::handoff);
        Looper.loop();
    }

    private void handoff() {
        IContentProvider provider = null;
        try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, authority);
            if (provider == null) throw new IllegalStateException("Host Provider unavailable");
            Bundle extras = new Bundle();
            extras.putString("token", token);
            extras.putBinder("binder", this);
            Bundle reply = IContentProviderUtils.callCompat(provider, null, authority, "attach", null, extras);
            if (reply == null || !reply.getBoolean("accepted")) {
                Log.w(TAG, "Launch permission revoked or expired; exiting");
                System.exit(0);
            }
            acceptedOnce = true;
            missedHandoffs = 0;
        } catch (Throwable error) {
            Log.e(TAG, "Host handoff failed", error);
            // Bounded lifecycle grace permits a Host process restart, never a different backend.
            if (++missedHandoffs >= (acceptedOnce ? 6 : 3)) System.exit(1);
        } finally {
            if (provider != null) {
                try { ActivityManagerApis.removeContentProviderExternal(authority, null); }
                catch (Throwable error) { Log.w(TAG, "Provider release failed", error); }
            }
        }
        handler.postDelayed(this::handoff, 10000);
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
        return super.onTransact(code, data, reply, flags);
    }
}
