package com.ai.limbs.plugins.privacyaudit;

/** Fixed, bounded OS queries. Never accepts free-form shell from the UI or a capability. */
public final class ShellCommands {
    private ShellCommands() {}
    public static String quote(String text) {return "'"+text.replace("'", "'\\''")+"'";}
    public static String wrap(String command) {return "sh -c "+quote("timeout 22 sh -c "+quote("set -o pipefail; "+command));}
    public static final String ID = "id -u";
    public static final String USERS = "pm list users";
    public static final String BOOT = "cat /proc/sys/kernel/random/boot_id";
    public static String packages(int user) {return "pm list packages --user "+AuditCore.checkedUser(user)+" -U";}
    public static String launchers(int user) {return "cmd package query-activities --brief --components --user "+AuditCore.checkedUser(user)+" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER";}
    public static String state(int uid) {if(uid<0)throw new IllegalArgumentException();return "cmd activity get-uid-state "+uid;}
    public static String ops(AuditCore.App app) {return "cmd appops get --user "+app.user+" "+quote(AuditCore.checkedPackage(app.name));}
    public static String detail(String pkg) {
        return "dumpsys -t 10 package "+quote(AuditCore.checkedPackage(pkg))+" | awk "+quote(
            "/^Packages:/ {section=1; print; next} /^Queries:/ {section=0} " +
            "section && (/Package \\[/ || /versionCode=/ || /pkgFlags=/ || /requested permissions:/ || /install permissions:/ || /runtime permissions:/ || /android.permission./ || /User [0-9]+:/) {print} " +
            "/android.permission.BIND_(VPN_SERVICE|ACCESSIBILITY_SERVICE|DEVICE_ADMIN|INPUT_METHOD)/ {print \"protectedRole=platform-binding\"} " +
            "/Permission Denial|SecurityException|DUMP TIMEOUT/ {bad=1} END {if(bad || !section) {if(bad) exit 42}}"
        );
    }
    public static final String NETWORK = "dumpsys -t 12 netstats --uid | awk "+quote(
        "BEGIN {print \"NET_V1\"} " +
        "/^UID stats:/ {section=1; seen=1; next} /^UID tag stats:/ {section=0} " +
        "/Permission Denial|SecurityException|DUMP TIMEOUT/ {bad=1} " +
        "section && /ident=/ {ok=0; uid=-1; set=\"\"; tag=\"\"; physical=($0 ~ /type=(0|1),/ && $0 !~ /type=17,/); " +
        "for(i=1;i<=NF;i++){if($i ~ /^uid=/){split($i,a,\"=\");uid=a[2]+0} if($i ~ /^set=/){split($i,a,\"=\");set=a[2]} if($i ~ /^tag=/){split($i,a,\"=\");tag=a[2]}} " +
        "ok=(physical && uid>=0 && tag==\"0x0\" && (set==\"DEFAULT\" || set==\"FOREGROUND\")); next} " +
        "section && ok && /st=/ {r=-1;t=-1;for(i=1;i<=NF;i++){if($i ~ /^rb=/){split($i,a,\"=\");r=a[2]+0} if($i ~ /^tb=/){split($i,a,\"=\");t=a[2]+0}} " +
        "if(r<0 || t<0){bad=1;next} rx[uid]+=r;tx[uid]+=t;if(set==\"DEFAULT\"){br[uid]+=r;bt[uid]+=t}} " +
        "END {if(bad || !seen)exit 42; for(u in rx)printf \"%d %.0f %.0f %.0f %.0f\\n\",u,rx[u],tx[u],br[u],bt[u]; print \"NET_END\"}"
    );
    public static final String GUARDS = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; " +
        "settings get secure enabled_accessibility_services; settings get secure default_input_method; " +
        "settings get secure always_on_vpn_app; dumpsys -t 5 device_policy | grep -E 'ComponentInfo|admin=|Admin|mOwners|Device Owner|Profile Owner'";
    public static String setEnabled(AuditCore.App app, int state) {
        AuditCore.checkedPackage(app.name); AuditCore.checkedUser(app.user);
        String verb;
        if(state==3)verb="disable-user";
        else if(state==0)verb="default-state";
        else if(state==1)verb="enable";
        else throw new IllegalArgumentException("Unsupported enabled state");
        return "pm "+verb+" --user "+app.user+" "+quote(app.name);
    }
    public static String settings(AuditCore.App app) {
        return "am start --user "+AuditCore.checkedUser(app.user)+" -a android.settings.APPLICATION_DETAILS_SETTINGS -d "+quote("package:"+AuditCore.checkedPackage(app.name));
    }
}
