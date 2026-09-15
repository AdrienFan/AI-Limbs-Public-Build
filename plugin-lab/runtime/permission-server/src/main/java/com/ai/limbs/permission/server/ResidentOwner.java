package com.ai.limbs.permission.server;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.system.Os;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Private lifetime protocol. Its Binder token belongs to the independent Core process. */
final class ResidentOwner {
    static final int TRANSACTION = 0x41494c;
    private static final String DESCRIPTOR = "ai_limbs.permission.resident.v1";
    private static final int VERSION = 1;
    private final Object lock = new Object();
    private final String instanceId = UUID.randomUUID().toString();
    private final String launchToken;
    private final Handler handler;
    private final Runnable resumeHost;
    private final Runnable stopServer;
    private Lease owner;
    private boolean terminating;

    private final class Lease implements IBinder.DeathRecipient {
        final IBinder lifetime;
        final String session;
        final int pid;
        boolean active;
        Lease(IBinder lifetime, String session, int pid) {
            this.lifetime = lifetime;
            this.session = session;
            this.pid = pid;
        }
        @Override public void binderDied() {
            synchronized (lock) {
                if (owner != this) return;
                owner = null;
                terminating = true;
            }
            // A lost active Core must not silently resume the Host business runtime.
            // Execute outside the lock, without waiting for a possibly blocked Host provider.
            stopServer.run();
        }
    }

    ResidentOwner(String launchToken, Handler handler, Runnable resumeHost, Runnable stopServer) {
        this.launchToken = launchToken;
        this.handler = handler;
        this.resumeHost = resumeHost;
        this.stopServer = stopServer;
    }

    boolean ownsRuntime() {
        synchronized (lock) { return owner != null || terminating; }
    }

    void applyHostResult(Runnable action) {
        synchronized (lock) {
            if (owner == null && !terminating) action.run();
        }
    }

    boolean transact(Parcel data, Parcel reply, int flags) throws RemoteException {
        if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0)
            throw new IllegalArgumentException("Resident lifetime calls require an acknowledgement");
        data.enforceInterface(DESCRIPTOR);
        if (data.readInt() != VERSION) throw new IllegalArgumentException("Resident lifetime protocol mismatch");
        int operation = data.readInt();
        Runnable afterReply = null;
        String result;
        synchronized (lock) {
            if (terminating) throw new IllegalStateException("Permission backend is terminating");
            if (operation == 0) {
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected description payload");
            } else if (operation == 1 || operation == 2 || operation == 3) {
                String token = data.readString();
                String session = data.readString();
                IBinder lifetime = data.readStrongBinder();
                if (!launchToken.equals(token)) throw new SecurityException("Permission launch was not authorized");
                if (session == null || session.length() < 1 || session.length() > 64 || lifetime == null)
                    throw new IllegalArgumentException("Invalid Core lifetime identity");
                int pid = Binder.getCallingPid();
                if (operation == 1 || operation == 3) {
                    if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected claim payload");
                    if (owner != null) {
                        requireOwner(pid, session, lifetime);
                    } else {
                        if (operation == 1) throw new IllegalStateException("Prepare the Core lifetime before activation");
                        Lease candidate = new Lease(lifetime, session, pid);
                        owner = candidate;
                        try {
                            lifetime.linkToDeath(candidate, 0);
                            if (!lifetime.isBinderAlive()) throw new RemoteException("Core died while claiming its backend");
                        } catch (RemoteException error) {
                            owner = null;
                            unlink(candidate);
                            throw error;
                        }
                    }
                    if (operation == 1) owner.active = true;
                } else {
                    int destination = data.readInt();
                    if (data.dataAvail() != 0 || (destination != 0 && destination != 1))
                        throw new IllegalArgumentException("Invalid ownership destination");
                    requireOwner(pid, session, lifetime);
                    Lease previous = owner;
                    owner = null;
                    unlink(previous);
                    if (destination == 1) {
                        afterReply = resumeHost;
                    } else {
                        terminating = true;
                        afterReply = stopServer;
                    }
                }
            } else {
                throw new IllegalArgumentException("Unknown Resident lifetime operation");
            }
            result = snapshotLocked();
        }
        reply.writeNoException();
        reply.writeString(result);
        if (afterReply != null) handler.post(afterReply);
        return true;
    }

    private void requireOwner(int pid, String session, IBinder lifetime) {
        if (owner == null || owner.pid != pid || !owner.session.equals(session) || !owner.lifetime.equals(lifetime))
            throw new SecurityException("Resident lifetime belongs to a different Core session");
    }

    private void unlink(Lease lease) {
        try { lease.lifetime.unlinkToDeath(lease, 0); }
        catch (java.util.NoSuchElementException error) {
            android.util.Log.w("AIL.ResidentOwner", "Core death link was already removed", error);
        }
    }

    private String snapshotLocked() {
        try {
            return new JSONObject()
                .put("protocol", VERSION)
                .put("instance_id", instanceId)
                .put("pid", Os.getpid())
                .put("uid", Os.getuid())
                .put("runtime_owner", terminating ? "stopping" : owner == null ? "android_host" : owner.active ? "resident_core" : "handoff_prepared")
                .put("core_pid", owner == null ? JSONObject.NULL : owner.pid)
                .put("core_session", owner == null ? JSONObject.NULL : owner.session)
                .put("core_lifetime_alive", owner != null && owner.lifetime.isBinderAlive())
                .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Cannot encode Resident lifetime status", error);
        }
    }
}
