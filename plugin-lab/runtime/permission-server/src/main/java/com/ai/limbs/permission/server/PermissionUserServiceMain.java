package com.ai.limbs.permission.server;

import android.content.IContentProvider;
import android.os.Bundle;
import android.os.Binder;
import android.os.Parcel;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import java.util.Objects;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IShizukuService;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.UserService;
import rikka.shizuku.server.api.IContentProviderUtils;

public final class PermissionUserServiceMain {
    private static Binder lifetime;
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            String name = null;
            int uid = -1;
            String launchToken = null;
            for (String arg : args) {
                if (arg.startsWith("--package=")) name = arg.substring(10);
                if (arg.startsWith("--token=")) launchToken = arg.substring(8);
                if (arg.startsWith("--uid=")) uid = Integer.parseInt(arg.substring(6));
            }
            if (name == null || uid < 10000) throw new IllegalArgumentException("Missing UserService app identity");
            // The client already holds the broker Binder. Its protected Provider supplies the
            // exact same endpoint; no official manager package or kernel service special case is needed.
            IBinder backend = clientBackend(name, uid / 100000);
            backend.linkToDeath(() -> System.exit(0), 0);
            lifetime = new Binder();
            Parcel claim = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                claim.writeInterfaceToken(PermissionUserServices.BOOTSTRAP_DESCRIPTOR);
                claim.writeString(Objects.requireNonNull(launchToken));
                claim.writeStrongBinder(lifetime);
                if (!backend.transact(PermissionUserServices.BOOTSTRAP_TRANSACTION, claim, reply, 0))
                    throw new SecurityException("Client is connected to a different permission server");
                reply.readException();
            } finally { claim.recycle(); reply.recycle(); }
            // Claim and register the real PID before loading any third-party code. A revoked
            // queued launch cannot load privileged app code before its token is checked.
            UserService.setTag("AIL.UserService");
            Pair<IBinder, String> created = Objects.requireNonNull(UserService.create(args), "UserService creation failed");
            Bundle options = new Bundle();
            options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, created.second);
            IShizukuService.Stub.asInterface(backend).attachUserService(created.first, options);
            Looper.loop();
        } catch (Throwable error) {
            Log.e("AIL.UserService", "UserService startup failed", error);
            System.exit(1);
        }
    }

    private static IBinder clientBackend(String name, int user) throws Exception {
        String authority = name + ".shizuku";
        IContentProvider provider = ActivityManagerApis.getContentProviderExternal(authority, user, null, authority);
        if (provider == null) throw new IllegalStateException("Client Shizuku Provider is unavailable");
        try {
            Bundle reply = Objects.requireNonNull(IContentProviderUtils.callCompat(provider, null, authority, "getBinder", null, new Bundle()));
            reply.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer value = reply.getParcelable("moe.shizuku.privileged.api.intent.extra.BINDER");
            return Objects.requireNonNull(Objects.requireNonNull(value).binder);
        } finally { ActivityManagerApis.removeContentProviderExternal(authority, null); }
    }
}
