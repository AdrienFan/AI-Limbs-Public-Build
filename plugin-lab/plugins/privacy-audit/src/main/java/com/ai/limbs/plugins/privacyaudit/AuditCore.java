package com.ai.limbs.plugins.privacyaudit;

import java.util.*;
import java.util.regex.*;

/** Pure evidence interpretation. Android commands and persistence live outside this class. */
public final class AuditCore {
    private AuditCore() {}
    public static final Set<String> SENSITIVE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "READ_CALL_LOG", "READ_SMS",
        "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_CALENDAR", "BODY_SENSORS",
        "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED",
        "READ_EXTERNAL_STORAGE", "GET_ACCOUNTS", "BLUETOOTH_SCAN", "ACTIVITY_RECOGNITION",
        "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE")));
    public static final Set<String> SENSITIVE_OPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CONTACTS",
        "READ_CALL_LOG", "READ_SMS", "READ_CALENDAR", "READ_CLIPBOARD", "BODY_SENSORS")));
    public static final Set<String> EXPOSURE_OPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES",
        "WRITE_SETTINGS", "ACTIVATE_VPN", "ESTABLISH_VPN_SERVICE")));
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*");
    public static String checkedPackage(String value) {
        if (value == null || value.length() > 240 || !PACKAGE.matcher(value).matches())
            throw new IllegalArgumentException("Invalid package identifier");
        return value;
    }
    public static int checkedUser(int value) {
        if (value < 0 || value > 21473) throw new IllegalArgumentException("Invalid user id");
        return value;
    }
    public static final class App {
        public final String name;
        public final int user, uid;
        public Boolean launcher;
        public boolean backgroundObserved, foregroundObserved;
        public App(String name, int user, int uid) {
            this.name = checkedPackage(name); this.user = checkedUser(user); this.uid = uid;
            if (uid < 0 || uid / 100000 != user) throw new IllegalArgumentException("UID/user mismatch");
        }
        public String key() { return user + ":" + name; }
        public int priority() {
            if (Boolean.FALSE.equals(launcher) && !foregroundObserved) return 1;
            if ((launcher == null && !foregroundObserved) || backgroundObserved) return 2;
            return 3;
        }
        public String classification() {
            if (launcher == null && !foregroundObserved) return "形态未知，暂列二级";
            if (Boolean.FALSE.equals(launcher) && !foregroundObserved) return "无启动入口，一级候选（不证明始终后台）";
            if (foregroundObserved) return backgroundObserved ? "已观察到前台与后台活动，二级" : "已观察到前台交互，三级候选";
            return backgroundObserved ? "有界面且观察到后台活动，二级" : "有界面，三级候选（尚未观察到后台活动）";
        }
    }
    public static final class Detail {
        public String version;
        public boolean system, installed, suspended, hidden;
        public int enabled = -1;
        public final Set<String> requested = new TreeSet<>(), granted = new TreeSet<>();
        public final Set<String> protectedRoles = new TreeSet<>();
    }
    public static final class Ops {
        public final Map<String, String> modes = new TreeMap<>();
        public final Set<String> running = new TreeSet<>();
        public final List<String> evidence = new ArrayList<>();
    }
    public static final class Traffic {
        public long rx, tx, bgRx, bgTx;
        public Traffic(long rx, long tx, long bgRx, long bgTx) {
            if (rx < 0 || tx < 0 || bgRx < 0 || bgTx < 0 || bgRx > rx || bgTx > tx)
                throw new IllegalArgumentException("Invalid traffic counters");
            this.rx = rx; this.tx = tx; this.bgRx = bgRx; this.bgTx = bgTx;
        }
    }
    public static final class Delta {
        public final Long tx, bgTx;
        public final String status;
        Delta(Long tx, Long bgTx, String status) { this.tx=tx; this.bgTx=bgTx; this.status=status; }
    }
    public static Delta delta(Traffic before, Traffic after, boolean sameBoot, boolean sameIdentity) {
        if (after == null) return new Delta(null, null, "无可用网络观测");
        if (before == null || !sameBoot || !sameIdentity) return new Delta(null, null, "建立基线");
        if (after.tx < before.tx || after.rx < before.rx || after.bgTx < before.bgTx || after.bgRx < before.bgRx)
            return new Delta(null, null, "计数回退，重建基线");
        return new Delta(after.tx-before.tx, after.bgTx-before.bgTx, "系统计数新增；不是精确的采样间隔流量");
    }
    public static final class Assessment {
        public String level = "UNKNOWN";
        public final List<String> reasons = new ArrayList<>();
        public Assessment add(String message) { reasons.add(message); return this; }
    }
    public static Assessment assess(Detail now, Detail before, Ops ops, Integer uidState,
                                    Delta traffic, boolean sharedUid, boolean expectedBackground) {
        Assessment a = new Assessment();
        if (now == null || ops == null || uidState == null || traffic == null || traffic.tx == null) {
            a.add("证据不完整或尚未形成网络基线；不能据此判为安全");
        } else { a.level = "R0"; a.add("本次已采样证据未触发异常规则；不等于无风险"); }
        if (now != null) {
            if (!now.granted.isEmpty()) a.add("敏感权限已授予："+String.join(", ", now.granted)+"；授权不证明实际使用");
            if (before != null) {
                Set<String> added = new TreeSet<>(now.granted); added.removeAll(before.granted);
                if (!added.isEmpty()) { a.level="R1"; a.add("配置变化：新授予敏感权限 "+String.join(", ", added)); }
                if (!Objects.equals(now.version, before.version)) a.add("版本发生变化；需要重新审阅证据");
            }
        }
        if (sharedUid) a.add("多个包共享 UID，运行及网络行为无法唯一归属，不给单包升级行为风险");
        if (ops != null) {
            Set<String> active = new TreeSet<>(ops.running); active.retainAll(SENSITIVE_OPS);
            if (!active.isEmpty()) {
                if (a.level.equals("UNKNOWN") || a.level.equals("R0")) a.level="R1";
                a.add("系统 AppOps 报告正在访问："+String.join(", ",active));
                // Only an ongoing operation can be compared with a current process state.
                // Historical last-access timestamps MUST NOT be labelled background accesses.
                if (!sharedUid && uidState != null && uidState >= 7 && uidState < 20 &&
                    traffic != null && traffic.bgTx != null && traffic.bgTx >= 1024 * 1024 && !expectedBackground) {
                    a.level="R2";
                    a.add("需核查：采样时后台敏感访问，与背景发送计数增加并存；时间粒度不同，不能证明敏感内容外传");
                }
            }
        }
        if (expectedBackground) a.add("用户已标注预期后台活动；原始证据仍保留");
        a.add("不读取通信明文、域名或用户授权意图；R3 未经授权外传不由这些信号自动判定");
        return a;
    }
    public static List<Integer> users(String text) {
        Set<Integer> out = new TreeSet<>();
        Matcher m=Pattern.compile("UserInfo\\{(\\d+):").matcher(text);
        while (m.find()) out.add(checkedUser(Integer.parseInt(m.group(1))));
        if (out.isEmpty()) throw new IllegalArgumentException("用户清单不可读或格式未识别");
        return new ArrayList<>(out);
    }
    public static List<App> packages(String text, int user) {
        List<App> out=new ArrayList<>();
        Pattern p=Pattern.compile("^package:([^\\s]+)\\s+uid:(\\d+)\\s*$");
        for (String line:text.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            Matcher m=p.matcher(line.trim());
            if (!m.matches()) throw new IllegalArgumentException("应用清单含未识别内容");
            out.add(new App(m.group(1),user,Integer.parseInt(m.group(2))));
        }
        // Samsung Secure Folder may return success + empty output even to shell.
        if(out.isEmpty()) throw new IllegalArgumentException("空清单：不能确认此用户空间可见性");
        return out;
    }
    public static Set<String> launchers(String text) {
        Set<String> out=new HashSet<>();
        if(text.trim().equals("No activities found")) return out;
        for(String line:text.split("\\r?\\n")) {
            String s=line.trim(); if(s.isEmpty()) continue;
            int slash=s.indexOf('/');
            if(slash<=0 || s.contains(" ")) throw new IllegalArgumentException("启动入口清单格式未识别");
            out.add(checkedPackage(s.substring(0,slash)));
        }
        if(out.isEmpty()) throw new IllegalArgumentException("启动入口返回空数据");
        return out;
    }
    public static Detail detail(String text, String name, int user) {
        checkedPackage(name); checkedUser(user);
        Detail d=new Detail(); boolean found=false, requested=false, install=false, target=false, userFound=false, flagsFound=false;
        Pattern up=Pattern.compile("^User (\\d+):.*");
        for(String raw:text.split("\\r?\\n")) {
            String s=raw.trim();
            if(s.startsWith("protectedRole=")) {d.protectedRoles.add(s.substring(14));continue;}
            if(s.startsWith("Package [")) { found=s.startsWith("Package ["+name+"]"); continue; }
            if(!found) continue;
            if(s.startsWith("versionCode=")) d.version=s.split("\\s+")[0].substring(12);
            if(s.startsWith("pkgFlags=")) {flagsFound=true;d.system=Pattern.compile("\\bSYSTEM\\b|\\bUPDATED_SYSTEM_APP\\b").matcher(s).find();}
            if(s.equals("requested permissions:")) {requested=true;install=false;continue;}
            if(s.equals("install permissions:")) {requested=false;install=true;continue;}
            Matcher um=up.matcher(s);
            if(um.matches()) {
                requested=false; install=false; target=Integer.parseInt(um.group(1))==user;
                if(target) {
                    userFound=true; d.installed=s.contains("installed=true"); d.suspended=s.contains("suspended=true");
                    d.hidden=s.contains("hidden=true");
                    Matcher em=Pattern.compile("\\benabled=(\\d+)").matcher(s); if(em.find())d.enabled=Integer.parseInt(em.group(1));
                }
                continue;
            }
            if(s.startsWith("android.permission.")) {
                String perm=s.substring("android.permission.".length()).split("[:\\s]",2)[0];
                if(SENSITIVE.contains(perm)) {
                    if(requested) d.requested.add(perm);
                    if((install || target) && s.contains("granted=true"))d.granted.add(perm);
                }
            }
        }
        if(!found || !userFound || !flagsFound || d.version==null || d.enabled<0)
            throw new IllegalArgumentException("包详情不完整或目标用户不可见");
        return d;
    }
    public static Ops appOps(String text) {
        Ops out=new Ops(); boolean recognized=false;
        Pattern p=Pattern.compile("(?:Uid mode: )?([A-Z][A-Z_0-9]+): (allow|ignore|deny|default|foreground|errored)(?:;.*)?");
        for(String raw:text.split("\\r?\\n")) {
            String s=raw.trim(); if(s.isEmpty()) continue;
            if(s.equals("No operations.") || s.startsWith("Default mode:")) {recognized=true;continue;}
            Matcher m=p.matcher(s);
            if(m.matches()) {
                recognized=true;out.modes.put(m.group(1),m.group(2));
                if(s.contains("(running)")) out.running.add(m.group(1));
                if(SENSITIVE_OPS.contains(m.group(1)) || EXPOSURE_OPS.contains(m.group(1))) out.evidence.add(s);
            } else if(s.contains("SecurityException") || s.contains("Permission Denial") || s.startsWith("Error"))
                throw new IllegalArgumentException("AppOps 访问被拒绝");
        }
        if(!recognized) throw new IllegalArgumentException("AppOps 格式未识别");
        return out;
    }
    public static int uidState(String value) {
        Matcher m=Pattern.compile("^(\\d+)\\s+\\([A-Z_]+\\)\\s*$").matcher(value.trim());
        if(!m.matches()) throw new IllegalArgumentException("UID 状态格式未识别");
        int state=Integer.parseInt(m.group(1));
        if(state>20)throw new IllegalArgumentException("UID 状态超出已知范围");
        return state;
    }
    public static Map<Integer, Traffic> traffic(String text) {
        Map<Integer, Traffic> out=new TreeMap<>(); boolean start=false,end=false;
        for(String line:text.split("\\r?\\n")) {
            if(line.equals("NET_V1")){start=true;continue;}
            if(line.equals("NET_END")){end=true;continue;}
            if(line.trim().isEmpty())continue;
            String[] p=line.trim().split("\\s+");
            if(p.length!=5 || !start || end)throw new IllegalArgumentException("网络采样格式不完整");
            int uid=Integer.parseInt(p[0]); if(uid<0 || out.containsKey(uid))throw new IllegalArgumentException("网络 UID 重复");
            out.put(uid,new Traffic(Long.parseLong(p[1]),Long.parseLong(p[2]),Long.parseLong(p[3]),Long.parseLong(p[4])));
        }
        if(!start || !end || out.isEmpty())throw new IllegalArgumentException("网络采样无有效 UID 计数");
        return out;
    }
    public static List<App> schedule(List<App> apps, Map<String, Long> sampledAt, long now, int[] quotas) {
        List<App> result=new ArrayList<>();
        for(int priority=1;priority<=3;priority++) {
            final int p=priority;
            List<App> eligible=new ArrayList<>();
            for(App a:apps) if(a.priority()==p)eligible.add(a);
            eligible.sort(Comparator.comparingLong((App a)->sampledAt.getOrDefault(a.key(),0L)).thenComparing(App::key));
            result.addAll(eligible.subList(0,Math.min(quotas[priority-1],eligible.size())));
        }
        return result;
    }
    public static void allowControl(App app, Detail detail, int uidPackageCount, Set<String> protectedPackages) {
        if(app.user!=0 || app.uid%100000<10000 || uidPackageCount!=1 || detail.system || !detail.installed ||
            !detail.protectedRoles.isEmpty() || protectedPackages.contains(app.name) ||
            app.name.startsWith("com.ai.assistance.operit"))
            throw new IllegalArgumentException("此组件受保护或身份不明确；仅支持主用户独立 UID 的普通应用");
    }
}
