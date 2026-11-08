package com.ai.limbs.permission.server;

import android.content.IContentProvider;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import moe.shizuku.api.BinderContainer;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ConfigPackageEntry;
import rikka.shizuku.server.api.IContentProviderUtils;

/** External authorization belongs to the permission plugin's backend, never to the kernel.
 * Only the verified Host can call management RPC. A delivered Binder conveys no authorization. */
final class ExternalClients {
    static final int TRANSACTION = 0x414950;
    static final String DESCRIPTOR = "ai_limbs.permission.clients.v1";
    private static final String TAG = "AIL.ExternalClients";
    private static final long REQUEST_LIFETIME = 120000;
    private final PermissionServer server;
    private final int hostUid;
    private final int userId;
    private final Object lock = new Object();
    private final String instance = UUID.randomUUID().toString();
    private final Map<Integer, Decision> decisions = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<Integer, List<Process>> processes = new HashMap<>();
    private final Set<Integer> delivered = new HashSet<>();
    private final Set<Integer> connecting = new HashSet<>();
    private final Map<Integer, String> delivery = new ConcurrentHashMap<>();
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private volatile boolean enabled;
    private boolean initialized;

    private static final class Decision {
        final String identity;
        final boolean allowed;
        final boolean persistent;
        Decision(String identity, boolean allowed, boolean persistent) {
            this.identity = identity; this.allowed = allowed; this.persistent = persistent;
        }
    }
    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final ClientRecord client;
        final int code;
        final String identity;
        final long expires = SystemClock.elapsedRealtime() + REQUEST_LIFETIME;
        Pending(ClientRecord client, int code, String identity) {
            this.client = client; this.code = code; this.identity = identity;
        }
    }
    private static final class Identity {
        final List<String> packages;
        final String digest;
        Identity(List<String> packages, String digest) { this.packages = packages; this.digest = digest; }
    }

    ExternalClients(PermissionServer server, int hostUid, int userId) {
        this.server = server; this.hostUid = hostUid; this.userId = userId;
    }

    private void requireApp(int uid) {
        if (uid == hostUid || uid / 100000 != userId || uid % 100000 < 10000)
            throw new SecurityException("Only apps in the AI Limbs Android user are supported");
    }

    @SuppressWarnings("deprecation")
    private Identity identity(int uid) {
        requireApp(uid);
        long token = Binder.clearCallingIdentity();
        try {
            List<String> names = new ArrayList<>(PackageManagerApis.getPackagesForUidNoThrow(uid));
            Collections.sort(names);
            if (names.isEmpty()) throw new SecurityException("App is no longer installed");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : names) {
                int flags = PackageManager.GET_SIGNATURES;
                if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(name, flags, userId);
                if (pi == null || pi.applicationInfo == null || pi.applicationInfo.uid != uid)
                    throw new SecurityException("Package identity changed");
                Signature[] signatures;
                if (Build.VERSION.SDK_INT >= 28) {
                    if (pi.signingInfo == null) throw new SecurityException("Signing information is unavailable");
                    signatures = pi.signingInfo.getApkContentsSigners();
                } else {
                    signatures = pi.signatures;
                }
                if (signatures == null || signatures.length == 0) throw new SecurityException("Missing signing certificate");
                List<String> certs = new ArrayList<>();
                for (Signature signature : signatures) certs.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
                Collections.sort(certs);
                digest.update((name + "\n" + pi.firstInstallTime + "\n" + String.join(",", certs) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return new Identity(names, hex(digest.digest()));
        } catch (SecurityException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot verify package identity", error); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private Decision validDecision(int uid, String fingerprint) {
        synchronized (lock) {
            Decision value = decisions.get(uid);
            return value != null && value.identity.equals(fingerprint) ? value : null;
        }
    }

    boolean allowed(int uid) {
        if (!enabled) return false;
        Identity current = identity(uid);
        Decision decision = validDecision(uid, current.digest);
        return decision != null && decision.allowed;
    }

    ConfigPackageEntry configEntry(int uid) {
        if (uid == hostUid) return null;
        Identity current = identity(uid);
        Decision value = validDecision(uid, current.digest);
        if (value == null) return null;
        final boolean allowed = enabled && value.allowed;
        return new ConfigPackageEntry() {
            @Override public boolean isAllowed() { return allowed; }
            @Override public boolean isDenied() { return !allowed; }
        };
    }

    void validateAttach(int uid, String name) {
        if (!enabled || !identity(uid).packages.contains(name)) throw new SecurityException("External access disabled or package/UID mismatch");
        PackageInfo pi = packageInfo(name);
        if (!compatible(pi)) throw new SecurityException("App does not publish a protected Shizuku Provider");
    }

    void enforceClient(int uid, int pid, boolean requiresGrant) {
        if (!enabled) throw new SecurityException("External access is disabled");
        ClientRecord client = server.getClientManager().requireClient(uid, pid);
        if (!identity(uid).packages.contains(client.packageName)) throw new SecurityException("Client identity changed");
        client.allowed = allowed(uid);
        if (requiresGrant && !client.allowed) throw new SecurityException("App is not authorized");
    }

    void request(ClientRecord client, int code) {
        Identity current = identity(client.uid);
        if (!enabled) { client.dispatchRequestPermissionResult(code, false); return; }
        Pending request;
        synchronized (lock) {
            for (Pending old : pending.values()) {
                if (old.client == client && old.code == code) return;
            }
            if (pending.size() >= 128) throw new IllegalStateException("Too many pending requests");
            request = new Pending(client, code, current.digest);
            pending.put(request.id, request);
        }
        // Keep callback code on the backend executor, outside all authorization locks.
        server.handler().postDelayed(() -> expire(request), REQUEST_LIFETIME);
    }

    private void expire(Pending request) {
        boolean removed;
        synchronized (lock) { removed = pending.remove(request.id, request); }
        if (removed && request.client.client.asBinder().isBinderAlive()) request.client.dispatchRequestPermissionResult(request.code, false);
    }

    JSONObject rpc(JSONObject args) throws Exception {
        String operation = args.getString("operation");
        switch (operation) {
            case "snapshot": break;
            case "restore": restore(args.getJSONObject("config")); break;
            case "enable":
                initialized = true;
                enabled = args.getBoolean("enabled");
                if (!enabled) revokeAll();
                break;
            case "connect":
                if (!enabled) throw new IllegalStateException("Enable external app access first");
                String name = args.getString("package_name");
                PackageInfo pi = packageInfo(name);
                if (!compatible(pi)) throw new IllegalArgumentException("No compatible Shizuku Provider");
                requireApp(pi.applicationInfo.uid);
                validateAttach(pi.applicationInfo.uid, name);
                synchronized (lock) {
                    if (!connecting.add(pi.applicationInfo.uid))
                        throw new IllegalStateException("该应用正在连接，请等待当前连接完成");
                }
                delivery.put(pi.applicationInfo.uid, "launching");
                try { connector.execute(() -> sendBinder(name, pi.applicationInfo.uid)); }
                catch (RejectedExecutionException error) {
                    synchronized (lock) { connecting.remove(pi.applicationInfo.uid); }
                    throw error;
                }
                break;
            case "decide":
                decide(args.getInt("uid"), args.getString("identity"), args.getString("mode"));
                break;
            default: throw new IllegalArgumentException("Unsupported external management operation");
        }
        return snapshot();
    }

    private void restore(JSONObject config) throws Exception {
        if (config.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported authorization schema");
        Map<Integer, Decision> next = new HashMap<>();
        JSONArray values = config.getJSONArray("decisions");
        if (values.length() > 1024) throw new IllegalArgumentException("Too many stored decisions");
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.getJSONObject(i);
            int uid = item.getInt("uid");
            requireApp(uid);
            String fingerprint = item.getString("identity");
            if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid stored identity");
            next.put(uid, new Decision(fingerprint, item.getBoolean("allowed"), true));
        }
        synchronized (lock) {
            // Restore is allowed only before enabling a fresh backend instance.
            if (initialized) throw new IllegalStateException("Backend authorization already initialized");
            initialized = true;
            decisions.putAll(next);
            enabled = config.getBoolean("enabled");
        }
    }

    private void decide(int uid, String expected, String mode) {
        Identity current = identity(uid);
        if (!current.digest.equals(expected)) throw new SecurityException("App identity changed; refresh before authorizing");
        if (!Arrays.asList("allow", "session", "deny", "revoke").contains(mode)) throw new IllegalArgumentException("Unknown decision");
        boolean allow = mode.equals("allow") || mode.equals("session");
        if (allow && !enabled) throw new IllegalStateException("External access is disabled");
        List<Pending> requests = new ArrayList<>();
        synchronized (lock) {
            decisions.put(uid, new Decision(current.digest, allow, !mode.equals("session")));
            Iterator<Pending> iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                Pending request = iterator.next();
                if (request.client.uid == uid) { requests.add(request); iterator.remove(); }
            }
        }
        for (ClientRecord client : server.getClientManager().findClients(uid)) client.allowed = allow;
        for (Pending request : requests) {
            if (request.client.client.asBinder().isBinderAlive())
                request.client.dispatchRequestPermissionResult(request.code,
                        allow && request.expires > SystemClock.elapsedRealtime() && request.identity.equals(current.digest));
        }
        if (!allow) terminate(uid, current.packages);
        Log.i(TAG, "External app decision uid=" + uid + ", mode=" + mode);
    }

    private PackageInfo packageInfo(String name) {
        long token = Binder.clearCallingIdentity();
        try { return PackageManagerApis.getPackageInfoNoThrow(name, PackageManager.GET_PROVIDERS, userId); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    private boolean compatible(PackageInfo pi) {
        if (pi == null || pi.applicationInfo == null || pi.applicationInfo.uid == hostUid || !pi.applicationInfo.enabled
                || pi.packageName.equals("moe.shizuku.privileged.api") || pi.providers == null) return false;
        for (ProviderInfo provider : pi.providers) {
            if ((pi.packageName + ".shizuku").equals(provider.authority) && provider.enabled && provider.exported
                    && "android.permission.INTERACT_ACROSS_USERS_FULL".equals(provider.readPermission)
                    && "android.permission.INTERACT_ACROSS_USERS_FULL".equals(provider.writePermission)) return true;
        }
        return false;
    }

    private void sendBinder(String name, int uid) {
        IContentProvider provider = null;
        String authority = name + ".shizuku";
        long token = Binder.clearCallingIdentity();
        try {
            validateAttach(uid, name);
            // On the observed Samsung device a non-running client Provider cannot be acquired.
            // A manual Connect explicitly opens the verified app and waits for activity launch;
            // provider acquisition is performed once after launch, never by waking all apps.
            ExternalAppLauncher.launch(name, uid, userId);
            validateAttach(uid, name);
            delivery.put(uid, "connecting");
            provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, authority);
            if (provider == null) throw new IllegalStateException("应用已启动，但 Shizuku Provider 仍不可用；请检查该应用的运行限制");
            if (!provider.asBinder().pingBinder()) throw new IllegalStateException("应用的 Shizuku Provider 已退出，请重新连接");
            Bundle extras = new Bundle();
            extras.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(server));
            Bundle reply = IContentProviderUtils.callCompat(provider, null, authority, "sendBinder", null, extras);
            if (reply == null) throw new IllegalStateException("应用的 Shizuku Provider 未接受连接信息");
            synchronized (lock) { delivered.add(uid); }
            delivery.put(uid, "sent");
        } catch (Exception error) {
            String detail = error.getMessage();
            String message = "连接失败：" + error.getClass().getSimpleName()
                    + (detail == null ? "" : " · " + detail);
            delivery.put(uid, message.substring(0, Math.min(message.length(), 600)));
            Log.w(TAG, "Client handoff failed: " + name, error);
        } finally {
            if (provider != null) {
                try { ActivityManagerApis.removeContentProviderExternal(authority, null); }
                catch (Exception error) { Log.w(TAG, "Client Provider release failed", error); }
            }
            synchronized (lock) { connecting.remove(uid); }
            Binder.restoreCallingIdentity(token);
        }
    }

    void trackProcess(int uid, Process process) {
        if (uid == hostUid) return;
        synchronized (lock) { List<Process> active = processes.computeIfAbsent(uid, key -> new ArrayList<>()); active.removeIf(p -> !p.isAlive()); active.add(process); }
        if (!allowed(uid)) process.destroyForcibly();
    }

    private void terminate(int uid, List<String> names) {
        for (ClientRecord client : server.getClientManager().findClients(uid)) client.allowed = false;
        List<Process> owned;
        synchronized (lock) { owned = processes.remove(uid); }
        if (owned != null) for (Process process : owned) process.destroyForcibly();
        long token = Binder.clearCallingIdentity();
        try {
            for (String name : names) {
                server.getUserServiceManager().removeUserServicesForPackage(name);
                ActivityManagerApis.forceStopPackageNoThrow(name, userId);
            }
        } finally { Binder.restoreCallingIdentity(token); }
    }

    private void revokeAll() {
        List<Pending> requests;
        Set<Integer> uids;
        synchronized (lock) {
            requests = new ArrayList<>(pending.values()); pending.clear();
            uids = new HashSet<>(delivered); uids.addAll(decisions.keySet()); uids.addAll(processes.keySet());
        }
        for (Pending request : requests) request.client.dispatchRequestPermissionResult(request.code, false);
        for (int uid : uids) {
            List<String> names = PackageManagerApis.getPackagesForUidNoThrow(uid);
            terminate(uid, names);
        }
    }

    void shutdown() {
        enabled = false;
        connector.shutdownNow();
        List<Process> owned = new ArrayList<>();
        synchronized (lock) { for (List<Process> list : processes.values()) owned.addAll(list); processes.clear(); }
        for (Process process : owned) process.destroyForcibly();
        server.getUserServiceManager().shutdown();
    }

    /** Called on the management poll. No background sweep starts arbitrary installed apps. */
    JSONObject snapshot() throws Exception {
        JSONArray apps = new JSONArray();
        List<PackageInfo> installed;
        long identityToken = Binder.clearCallingIdentity();
        try { installed = new ArrayList<>(PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PROVIDERS, userId)); }
        finally { Binder.restoreCallingIdentity(identityToken); }
        installed.sort(Comparator.comparing(pi -> pi.packageName));
        Set<Integer> seen = new HashSet<>();
        for (PackageInfo pi : installed) {
            if (!compatible(pi)) continue;
            int uid = pi.applicationInfo.uid;
            if (uid / 100000 != userId || uid % 100000 < 10000 || !seen.add(uid)) continue;
            Identity current = identity(uid);
            Decision decision = validDecision(uid, current.digest);
            int waiting;
            synchronized (lock) { waiting = (int) pending.values().stream().filter(p -> p.client.uid == uid).count(); }
            List<ClientRecord> clients = server.getClientManager().findClients(uid);
            JSONArray packages = new JSONArray(current.packages);
            apps.put(new JSONObject().put("uid", uid).put("package_name", pi.packageName).put("packages", packages)
                    .put("identity", current.digest).put("pending", waiting).put("connected", !clients.isEmpty())
                    .put("delivery", delivery.getOrDefault(uid, "idle"))
                    .put("decision", decision == null ? "ask" : decision.allowed ? decision.persistent ? "allow" : "session" : "deny"));
            // Replaced certificates/install identity must not inherit a stale live grant.
            if (decision == null && clients.stream().anyMatch(client -> client.allowed)) terminate(uid, current.packages);
        }
        JSONArray stored = new JSONArray();
        int waiting;
        synchronized (lock) {
            waiting = pending.size();
            for (Map.Entry<Integer, Decision> item : decisions.entrySet()) {
                if (item.getValue().persistent) stored.put(new JSONObject().put("uid", item.getKey())
                        .put("identity", item.getValue().identity).put("allowed", item.getValue().allowed));
            }
        }
        return new JSONObject().put("initialized", initialized).put("protocol", 1).put("instance_id", instance).put("enabled", enabled)
                .put("user_id", userId).put("pending", waiting).put("apps", apps)
                .put("config", new JSONObject().put("schema", 1).put("enabled", enabled).put("decisions", stored));
    }
}
