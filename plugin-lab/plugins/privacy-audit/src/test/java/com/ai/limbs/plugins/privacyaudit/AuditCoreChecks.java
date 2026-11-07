package com.ai.limbs.plugins.privacyaudit;

import java.util.*;

/** Runs with a plain JDK as well as in CI; fixtures contain no device data. */
public final class AuditCoreChecks {
    static int checks=0;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
    static AuditCore.Detail detail() {
        return AuditCore.detail("Package [example.app] (ab):\nversionCode=2 minSdk=26\npkgFlags=[ HAS_CODE ]\nrequested permissions:\nandroid.permission.CAMERA\ninstall permissions:\nUser 0: installed=true hidden=false suspended=false enabled=0\nruntime permissions:\nandroid.permission.CAMERA: granted=false\nUser 150: installed=true hidden=false suspended=false enabled=0\nruntime permissions:\nandroid.permission.CAMERA: granted=true", "example.app",0);
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("--network-command")){System.out.println(ShellCommands.NETWORK);return;}
        if(args.length>0 && args[0].equals("--traffic-file")) {
            Map<Integer,AuditCore.Traffic> rows=AuditCore.traffic(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),java.nio.charset.StandardCharsets.UTF_8));
            check(rows.size()==1);AuditCore.Traffic t=rows.get(10001);
            check(t.rx==40 && t.tx==60 && t.bgRx==30 && t.bgTx==40);
            System.out.println("PASS: actual AWK collector excludes VPN/debug/tag duplication");return;
        }
        rejects(()->AuditCore.checkedPackage("pkg;rm -r /"));
        rejects(()->AuditCore.checkedPackage("$(id)"));
        rejects(()->AuditCore.checkedUser(-1));
        check(ShellCommands.quote("a'b").equals("'a'\\''b'"));
        check(AuditCore.users("Users:\nUserInfo{0:Owner:x}\nUserInfo{150:Private:x}").equals(Arrays.asList(0,150)));
        rejects(()->AuditCore.users("Permission Denial"));
        rejects(()->AuditCore.packages("",150));
        rejects(()->AuditCore.packages("package:example.app uid:10001",150));
        AuditCore.App app=AuditCore.packages("package:example.app uid:10001",0).get(0);
        check(app.key().equals("0:example.app"));
        check(ShellCommands.setEnabled(app,0).equals("pm default-state --user 0 'example.app'"));
        check(ShellCommands.setEnabled(app,1).equals("pm enable --user 0 'example.app'"));
        check(ShellCommands.setEnabled(app,3).equals("pm disable-user --user 0 'example.app'"));
        rejects(()->ShellCommands.setEnabled(app,2));
        check(app.priority()==2); app.launcher=false;check(app.priority()==1);
        app.launcher=true;check(app.priority()==3);app.backgroundObserved=true;check(app.priority()==2);
        AuditCore.App deepLink=new AuditCore.App("example.deeplink",0,10101);deepLink.launcher=false;deepLink.foregroundObserved=true;
        check(deepLink.priority()==3);deepLink.backgroundObserved=true;check(deepLink.priority()==2);
        check(AuditCore.launchers("example.app/.Main\nother.app/Activity").size()==2);
        rejects(()->AuditCore.launchers(""));
        check(AuditCore.launchers("No activities found").isEmpty());
        AuditCore.Detail d=detail();check(d.granted.isEmpty());check(d.requested.contains("CAMERA"));
        rejects(()->AuditCore.detail("", "example.app",0));
        rejects(()->AuditCore.detail("Package [example.app] (ab):\nversionCode=2\nUser 0: installed=true enabled=0", "example.app",0));
        AuditCore.Ops historical=AuditCore.appOps("CAMERA: allow; time=+2h0m0s ago\nRECORD_AUDIO: ignore; rejectTime=+1s ago");
        check(historical.running.isEmpty());
        AuditCore.Ops active=AuditCore.appOps("CAMERA: allow; time=+4s ago (running)");
        check(active.running.contains("CAMERA"));
        check(AuditCore.appOps("No operations.\nDefault mode: default").running.isEmpty());
        rejects(()->AuditCore.appOps("Permission Denial"));
        rejects(()->AuditCore.appOps("CAMERA: allow\nSecurityException: denied"));
        check(AuditCore.uidState("4 (FOREGROUND_SERVICE)")==4);
        rejects(()->AuditCore.uidState("unavailable"));
        Map<Integer,AuditCore.Traffic> counters=AuditCore.traffic("NET_V1\n10001 100 50 60 20\nNET_END\n");
        check(counters.get(10001).bgTx==20);
        rejects(()->AuditCore.traffic("NET_V1\n10001 100 50 60 20"));
        rejects(()->AuditCore.traffic("NET_V1\nNET_END\n"));
        rejects(()->AuditCore.traffic("NET_V1\n10001 1 1 2 2\nNET_END\n"));
        AuditCore.Traffic a=new AuditCore.Traffic(1,1,0,0), b=new AuditCore.Traffic(10,2000001,5,2000000);
        AuditCore.Delta delta=AuditCore.delta(a,b,true,true);
        check(delta.tx==2000000);
        check(AuditCore.delta(a,b,false,true).tx==null);
        check(AuditCore.delta(a,b,true,false).tx==null);
        check(AuditCore.delta(b,a,true,true).tx==null);
        check(AuditCore.assess(d,null,historical,9,delta,false,false).level.equals("R0"));
        check(AuditCore.assess(d,null,active,9,delta,false,false).level.equals("R2"));
        check(AuditCore.assess(d,null,active,9,delta,true,false).level.equals("R1"));
        check(AuditCore.assess(d,null,active,4,delta,false,false).level.equals("R1"));
        check(AuditCore.assess(d,null,active,9,delta,false,true).level.equals("R1"));
        check(AuditCore.assess(null,null,null,null,delta,false,false).level.equals("UNKNOWN"));
        AuditCore.Detail granted=detail();granted.granted.add("CAMERA");
        check(AuditCore.assess(granted,null,historical,9,delta,false,false).level.equals("R0"));
        check(AuditCore.assess(granted,d,historical,9,delta,false,false).level.equals("R1"));
        AuditCore.App newer=new AuditCore.App("example.new",0,10002);newer.launcher=true;newer.backgroundObserved=true;
        Map<String,Long> ages=new HashMap<>();ages.put(app.key(),50L);
        check(AuditCore.schedule(Arrays.asList(app,newer),ages,100L,new int[]{1,1,1}).get(0).name.equals("example.new"));
        AuditCore.allowControl(app,d,1,new HashSet<>());checks++;
        rejects(()->AuditCore.allowControl(app,d,2,new HashSet<>()));
        rejects(()->AuditCore.allowControl(app,d,1,new HashSet<>(Arrays.asList(app.name))));
        d.system=true;rejects(()->AuditCore.allowControl(app,d,1,new HashSet<>()));
        System.out.println("PASS: "+checks+" evidence, parser, scheduling and control checks");
    }
}
