package com.ai.limbs.plugins.privacyaudit

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AuditController(private val host: InProcessPluginHost) {
    private val store=AuditStore(host.dataDir)
    private val bridge=AuditBridge(host)
    private val scanMutex=Mutex()
    private val jobMutex=Mutex()
    private val plans=ConcurrentHashMap<String,JSONObject>()
    private val parentJob=SupervisorJob(host.scope.coroutineContext[Job])
    private val scope=CoroutineScope(host.scope.coroutineContext+parentJob+Dispatchers.IO)
    @Volatile private var progress="尚未采样"
    @Volatile private var scanJob: Job?=null
    @Volatile private var exportJob: Job?=null
    private var loop: Job?=null
    private fun enabled()=store.get("enabled")=="true"
    private fun interval()=(store.get("interval")?.toInt() ?: 15).coerceIn(5,60)
    private fun retention()=(store.get("retention")?.toInt() ?: 30).coerceIn(7,90)
    suspend fun mount() {
        store.prune(retention())
        store.event("runtime_mount",JSONObject().put("version",VERSION).put("continuous_requested",enabled())
            .put("coverage","仅在插件已挂载且宿主与权限后端可用期间采样；停止期间存在空窗"))
        publishAlerts()
        if(enabled()) startLoop()
        scope.launch {while(isActive){delay(3_600_000);store.prune(retention())}}
    }
    suspend fun stop() {
        parentJob.cancelAndJoin()
        store.event("runtime_stop",JSONObject().put("continuous_requested",enabled()))
        try {bridge.attention(0)} catch(e: Exception) {host.logger.w("PrivacyAudit","Cannot clear attention: ${e.message}")}
        store.close()
    }
    suspend fun command(op: String, p: JSONObject): JSONObject = withContext(scope.coroutineContext) { when(op) {
        "status" -> status(p)
        "scan" -> {enqueueScan(null);ok().put("state","queued").put("message","采样已交给插件；可在页面查看进度")}
        "inspect" -> {val key=p.getString("key");require(store.app(key)!=null){"未知应用，请先扫描清单"};enqueueScan(key);ok().put("state","queued")}
        "configure" -> {
            if(p.has("interval_minutes")) {val n=p.getInt("interval_minutes");require(n in 5..60);store.put("interval",n.toString())}
            if(p.has("retention_days")) {val n=p.getInt("retention_days");require(n in 7..90);store.put("retention",n.toString())}
            if(p.has("notifications"))store.put("notifications",p.getBoolean("notifications").toString())
            if(p.has("enabled")) {
                val on=p.getBoolean("enabled");store.put("enabled",on.toString())
                if(on)startLoop() else {loop?.cancelAndJoin();loop=null;scanJob?.cancelAndJoin();progress="已暂停，历史仍可查看"}
            }
            store.event("configuration",JSONObject(p.toString()));status(JSONObject())
        }
        "history" -> ok().put("history",store.history(p.optInt("limit",50),p.optLong("before",Long.MAX_VALUE)))
        "verify" -> ok().put("verification",store.verify())
        "expectation" -> {
            val key=p.getString("key");require(store.app(key)!=null)
            store.put("expected:$key",p.getBoolean("expected_background").toString())
            store.event("expectation",JSONObject(p.toString()));ok()
        }
        "acknowledge" -> {store.put("acknowledged_at",System.currentTimeMillis().toString());bridge.attention(0);ok()}
        "settings" -> {
            val app=AuditJson.app(requireNotNull(store.app(p.getString("key"))))
            bridge.shell(ShellCommands.settings(app),true);ok().put("message","系统应用设置已打开，权限、后台限制及禁用由你选择")
        }
        "plan_disable", "plan_restore" -> plan(p.getString("key"),op=="plan_disable")
        "apply_control" -> apply(p.getString("token"),p.getString("confirm_package"))
        "clear_history" -> {require(p.optBoolean("confirm"));store.clearHistory();ok()}
        "export" -> {
            require(p.optBoolean("confirm")){"导出需要明确确认：下载目录内其他应用可能读取此报告"}
            jobMutex.withLock {
                check(exportJob?.isActive!=true){"已有导出任务正在执行"}
                store.put("export_result",JSONObject().put("state","queued").toString())
                exportJob=scope.launch {
                    try {export()}
                    catch(e: CancellationException){store.put("export_result",JSONObject().put("state","interrupted").toString());throw e}
                    catch(e: Exception){store.put("export_result",JSONObject().put("state","failed").put("error",e.message).toString());host.logger.w("PrivacyAudit","Export failed",e)}
                }
            }
            ok().put("state","queued").put("message","本地导出已排队；完成后的文件路径会显示在首页")
        }
        else -> error("Unsupported operation: $op")
    } }
    private fun startLoop() {
        if(loop?.isActive==true)return
        loop=scope.launch {
            while(isActive && enabled()) {
                if(scanJob?.isActive!=true)enqueueScan(null)
                scanJob?.join()
                delay(interval()*60_000L)
            }
        }
    }
    private suspend fun enqueueScan(key: String?) = jobMutex.withLock {
        check(scanJob?.isActive!=true){"已有一次采样正在执行"}
        scanJob=scope.launch {
            scanMutex.withLock {
                try {scan(key)}
                catch(e: CancellationException) {progress="采样已暂停；未完成项目不计为覆盖";throw e}
                catch(e: Exception) {
                    progress="采样失败：${e.message}"
                    store.put("last_error",progress)
                    store.event("scan_failed",JSONObject().put("error",e.message).put("coverage","本轮失败不等于安全"))
                    host.logger.w("PrivacyAudit",progress,e)
                }
            }
        }
    }
    private suspend fun scan(onlyKey: String?) {
        val start=System.currentTimeMillis();progress="检查采集能力"
        val caller=bridge.shell(ShellCommands.ID).trim()
        check(caller=="2000") {"当前执行身份 uid=$caller；本版需现有 ADB/Shizuku Shell 授权，不会申请 Root 或自动改变权限"}
        val boot=bridge.shell(ShellCommands.BOOT).trim()
        check(Regex("[0-9a-fA-F-]{36}").matches(boot)){"无法确定开机会话，暂停计数比较"}
        val errors=JSONArray();val coverage=JSONArray();val discovered=ArrayList<AuditCore.App>()
        val users=AuditCore.users(bridge.shell(ShellCommands.USERS))
        val successfulUsers=mutableSetOf<Int>()
        for(user in users) {
            progress="读取用户 $user 的应用与启动入口"
            try {
                val list=AuditCore.packages(bridge.shell(ShellCommands.packages(user)),user)
                var launcherSet: Set<String>?=null
                try {launcherSet=AuditCore.launchers(bridge.shell(ShellCommands.launchers(user)))}
                catch(e: CancellationException){throw e}
                catch(e: Exception){errors.put(JSONObject().put("source","launcher:$user").put("error",e.message))}
                for(app in list) {
                    app.launcher=launcherSet?.contains(app.name)
                    val old=store.app(app.key())
                    app.backgroundObserved=old!=null && old.optInt("uid")==app.uid && old.optBoolean("background_observed")
                    app.foregroundObserved=old!=null && old.optInt("uid")==app.uid && old.optBoolean("foreground_observed")
                    discovered.add(app)
                }
                successfulUsers.add(user)
                coverage.put(JSONObject().put("user",user).put("status","enumerated").put("packages",list.size)
                    .put("scope","此 Shell 可见清单；不证明覆盖隔离容器、隐藏进程或所有系统服务"))
            } catch(e: CancellationException){throw e}
            catch(e: Exception){coverage.put(JSONObject().put("user",user).put("status","unobservable").put("reason",e.message))}
        }
        check(discovered.isNotEmpty()){"所有用户空间均未取得可靠清单"}
        progress="读取系统 UID 流量计数"
        var network: Map<Int,AuditCore.Traffic>?=null
        try {network=AuditCore.traffic(bridge.shell(ShellCommands.NETWORK))}
        catch(e: CancellationException){throw e}
        catch(e: Exception){errors.put(JSONObject().put("source","network").put("error",e.message))}
        val now=System.currentTimeMillis()
        var services: JSONArray?=null
        try {
            val raw=bridge.shell("service list")
            val names=raw.lineSequence().mapNotNull {Regex("^\\d+\\s+([^:]+):\\s*\\[([^]]+)\\]").find(it.trim())}
                .map {JSONObject().put("name",it.groupValues[1]).put("interface",it.groupValues[2])}.toList()
            check(names.isNotEmpty()){"系统 Binder 服务清单格式未识别"}
            services=JSONArray(names)
        } catch(e: CancellationException){throw e}
        catch(e: Exception){errors.put(JSONObject().put("source","system_services").put("error",e.message))}
        val priorBoot=store.get("network_boot")
        val priorNetwork=store.get("network")?.let(::JSONObject)
        val uidCounts=discovered.groupingBy {it.uid}.eachCount()
        val keys=discovered.map {it.key()}.toSet()
        // Lost visibility is never turned into a fabricated uninstall event.
        for(old in store.apps()) if(old.getString("key") !in keys) {
            old.put("inventory_current",false).put("coverage",if(old.getInt("user") in successfulUsers)"本轮清单未见（卸载或可见性改变）" else "本轮无法观测该用户")
            store.saveApp(old)
        }
        val deltas=mutableMapOf<Int,AuditCore.Delta>()
        for(app in discovered) {
            val old=store.app(app.key())
            val sameIdentity=old!=null && old.optInt("uid")==app.uid && old.optBoolean("inventory_current")
            val before=priorNetwork?.optJSONObject(app.uid.toString())?.let(AuditJson::traffic)
            val delta=AuditCore.delta(before,network?.get(app.uid),priorBoot==boot,sameIdentity)
            deltas[app.uid]=delta
            if(delta.bgTx!=null && delta.bgTx>0)app.backgroundObserved=true
            val j=if(sameIdentity)JSONObject(old.toString()) else JSONObject()
            j.put("key",app.key()).put("package",app.name).put("user",app.user).put("uid",app.uid)
                .put("launcher",app.launcher ?: JSONObject.NULL).put("background_observed",app.backgroundObserved)
                .put("foreground_observed",app.foregroundObserved)
                .put("priority",app.priority()).put("classification",app.classification()).put("inventory_current",true)
                .put("inventory_at",now).put("coverage","清单可见，深度采样时间请单独查看")
                .put("shared_uid_packages",uidCounts.getValue(app.uid)).put("network_at",if(network!=null)now else JSONObject.NULL)
                .put("tx_added",delta.tx ?: JSONObject.NULL).put("bg_tx_added",delta.bgTx ?: JSONObject.NULL).put("network_status",delta.status)
            if(!j.has("risk")) j.put("risk","UNKNOWN").put("detail_at",0L)
            if(network!=null && network[app.uid]!=null)j.put("traffic",AuditJson.traffic(network.getValue(app.uid)))
            else j.remove("traffic")
            if(!sameIdentity) store.event("inventory_added_or_identity_changed",JSONObject().put("key",app.key()).put("uid",app.uid))
            store.saveApp(j)
        }
        if(network!=null) {
            val counters=JSONObject();network.forEach {(uid,t)->counters.put(uid.toString(),AuditJson.traffic(t))}
            store.put("network",counters.toString());store.put("network_boot",boot)
        }
        val sampleTimes=store.apps().associate {it.getString("key") to it.optLong("attempt_at",0L)}
        val selected=if(onlyKey==null)AuditCore.schedule(discovered,sampleTimes,now,intArrayOf(12,8,4)) else discovered.filter {it.key()==onlyKey}
        check(onlyKey==null || selected.isNotEmpty()){"指定应用当前不可见"}
        var done=0
        for(app in selected) {
            currentCoroutineContext().ensureActive();progress="深度采样 ${++done}/${selected.size} · 用户 ${app.user} · ${app.name}"
            inspect(app,deltas[app.uid],uidCounts.getValue(app.uid)>1,errors)
        }
        val summary=JSONObject().put("at",System.currentTimeMillis()).put("started_at",start).put("boot",boot)
            .put("users",coverage).put("visible_packages",discovered.size).put("sampled_this_cycle",done)
            .put("network","系统 UID 物理网络计数；排除 VPN 虚拟接口与调试集；VPN/共享 UID 归属仍可能不准确")
            .put("network_observable",network!=null).put("errors",errors)
            .put("visible_binder_services",services ?: JSONObject.NULL).put("service_scope","仅列出可见 Binder 服务，不等于取得所有系统守护进程的行为记录")
            .put("unmapped_network_uids",JSONArray(network?.keys?.filter {uid -> discovered.none {it.uid==uid}}?.sorted() ?: emptyList<Int>()))
        store.put("summary",summary.toString());store.put("last_error","");store.event("scan_completed",summary)
        store.prune(retention());progress="本轮完成：${discovered.size} 个可见应用，$done 个深度样本"
        publishAlerts()
    }
    private suspend fun inspect(app: AuditCore.App, delta: AuditCore.Delta?, shared: Boolean, errors: JSONArray) {
        val old=requireNotNull(store.app(app.key()));val sampled=System.currentTimeMillis()
        val previous=old.optJSONObject("detail")?.let(AuditJson::detail)
        var detail: AuditCore.Detail?=null;var ops: AuditCore.Ops?=null;var state: Int?=null
        val failures=JSONArray()
        suspend fun <T> collect(source: String, action: suspend ()->T): T? = try {action()}
            catch(e: CancellationException){throw e}
            catch(e: Exception){failures.put(JSONObject().put("source",source).put("error",e.message));null}
        detail=collect("package"){AuditCore.detail(bridge.shell(ShellCommands.detail(app.name)),app.name,app.user)}
        ops=collect("appops"){AuditCore.appOps(bridge.shell(ShellCommands.ops(app)))}
        state=collect("uid_state"){AuditCore.uidState(bridge.shell(ShellCommands.state(app.uid)))}
        val expected=store.get("expected:${app.key()}")=="true"
        val result=AuditCore.assess(detail,previous,ops,state,delta,shared,expected)
        if(state!=null && state in 4..11)app.backgroundObserved=true
        if(!shared && state!=null && state in 2..3)app.foregroundObserved=true
        old.put("risk",result.level).put("risk_at",sampled).put("reasons",JSONArray(result.reasons)).put("attempt_at",sampled)
            .put("background_observed",app.backgroundObserved).put("priority",app.priority()).put("classification",app.classification())
            .put("foreground_observed",app.foregroundObserved)
            .put("sample_errors",failures).put("uid_state",state ?: JSONObject.NULL).put("expected_background",expected)
            .put("appops_evidence",if(ops!=null)JSONArray(ops.evidence) else JSONObject.NULL)
            .put("active_ops",if(ops!=null)JSONArray(ops.running.toList()) else JSONObject.NULL)
        if(detail!=null) {old.put("detail",AuditJson.detail(detail)).put("detail_at",sampled)}
        // Preserve last successful detail with its old timestamp; never relabel it as a fresh read.
        store.saveApp(old)
        store.event("evidence",JSONObject(old.toString()))
        if(failures.length()>0)errors.put(JSONObject().put("key",app.key()).put("sources",failures))
    }
    private suspend fun publishAlerts() {
        val acknowledged=store.get("acknowledged_at")?.toLong() ?: 0L
        val fresh=store.apps().filter {it.optBoolean("inventory_current") && it.optString("risk")=="R2" && it.optLong("risk_at")>acknowledged}
        try {bridge.attention(fresh.size)}catch(e: CancellationException){throw e}catch(e: Exception){store.event("alert_delivery_failed",JSONObject().put("error",e.message))}
        val last=store.get("notification_at")?.toLong() ?: 0L
        if(fresh.isNotEmpty() && store.get("notifications")=="true" && System.currentTimeMillis()-last>6*60*60_000L) {
            try {
                val response=JSONObject(host.invokeHostCapability("host.notification@1",JSONObject().put("operation","publish")
                    .put("title","隐私审计：有行为证据需核查").put("message","${fresh.size} 个已采样应用触发 R2 规则。请打开隐私安全审计查看证据；这不代表已证实窃密。").toString()))
                check(response.optBoolean("success")){response.optString("error","通知未获许可")}
                store.put("notification_at",System.currentTimeMillis().toString())
            }catch(e: CancellationException){throw e}catch(e: Exception){store.event("notification_failed",JSONObject().put("error",e.message))}
        }
    }
    private fun status(p: JSONObject): JSONObject {
        val query=p.optString("query").lowercase();val offset=p.optInt("offset",0).coerceAtLeast(0);val limit=p.optInt("limit",30).coerceIn(1,100)
        val all=store.apps();val listed=all.filter {query.isBlank() || it.getString("package").lowercase().contains(query)}
            .sortedWith(compareBy<JSONObject> {when(it.optString("risk")){"R2"->0;"R1"->1;"UNKNOWN"->2;else->3}}
                .thenBy {it.optInt("priority",2)}.thenBy {it.getString("key")})
        return ok().put("version",VERSION).put("enabled",enabled()).put("interval_minutes",interval()).put("retention_days",retention())
            .put("notifications",store.get("notifications")=="true").put("busy",scanJob?.isActive==true).put("progress",progress)
            .put("summary",store.get("summary")?.let(::JSONObject) ?: JSONObject.NULL).put("last_error",store.get("last_error").orEmpty())
            .put("total",listed.size).put("offset",offset).put("apps",JSONArray(listed.drop(offset).take(limit)))
            .put("export_result",store.get("export_result")?.let(::JSONObject) ?: JSONObject.NULL)
            .put("deep_samples",all.count {it.optLong("detail_at")>0}).put("limits",JSONArray(LIMITS))
            .put("managed_disables",JSONObject(store.get("disables") ?: "{}"))
    }
    private suspend fun protectedPackages(): Set<String> {
        val out=mutableSetOf(host.applicationContext.packageName)
        val home=bridge.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        val h=Regex("([A-Za-z][A-Za-z0-9_.]+)/[^\\s]+").find(home) ?: error("无法确定默认桌面，禁止停用")
        out.add(h.groupValues[1])
        for(setting in listOf("enabled_accessibility_services","default_input_method","always_on_vpn_app")) {
            val v=bridge.shell("settings --user 0 get secure $setting").trim()
            check(v.isNotEmpty()){"关键组件查询为空，禁止停用"}
            if(v!="null") v.split(':').forEach {item->out.add(AuditCore.checkedPackage(item.substringBefore('/')))}
        }
        val admins=bridge.shell("dumpsys -t 5 device_policy",maxChars=500_000)
        check(admins.contains("Device Policy") || admins.contains("DevicePolicy") || admins.contains("Enabled Device Admins")){"无法识别设备管理状态，禁止停用"}
        Regex("([A-Za-z][A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").findAll(admins).forEach {out.add(it.groupValues[1])}
        return out
    }
    private suspend fun plan(key: String, suspend: Boolean): JSONObject = scanMutex.withLock {
        check(!enabled()){"请先暂停连续监测再准备系统操作"}
        check(bridge.shell(ShellCommands.ID).trim()=="2000"){"控制操作仅允许已授权的 Shell 后端"}
        val app=AuditJson.app(requireNotNull(store.app(key)))
        val inventory=AuditCore.packages(bridge.shell(ShellCommands.packages(app.user)),app.user)
        check(inventory.any {it.key()==key && it.uid==app.uid}){"应用身份已改变"}
        val d=AuditCore.detail(bridge.shell(ShellCommands.detail(app.name)),app.name,app.user)
        val ops=AuditCore.appOps(bridge.shell(ShellCommands.ops(app)))
        val protected=protectedPackages()
        check(ops.modes["ACTIVATE_VPN"]!="allow" && ops.modes["ESTABLISH_VPN_SERVICE"]!="allow"){"VPN 组件受保护"}
        AuditCore.allowControl(app,d,inventory.count {it.uid==app.uid},protected)
        check(AuditCore.uidState(bridge.shell(ShellCommands.state(app.uid)))>=4){"当前前台应用不允许停用"}
        val managed=JSONObject(store.get("disables") ?: "{}")
        if(suspend)check(d.enabled in 0..1 && !d.suspended){"应用不是正常启用状态，不能接管其他操作的状态"}
        else {
            check(managed.has(key) && d.enabled==3){"只恢复本插件有记录且当前仍停用的应用"}
            val original=managed.getJSONObject(key).getJSONObject("plan")
            check(original.getInt("uid")==app.uid && original.getString("version")==d.version){"应用身份或版本已改变，请在系统设置中恢复"}
        }
        val restoreState=if(suspend)d.enabled else managed.getJSONObject(key).getJSONObject("plan").getInt("restore_enabled")
        check(restoreState in 0..1){"没有可验证的恢复状态"}
        val token=UUID.randomUUID().toString()
        val plan=JSONObject().put("token",token).put("key",key).put("package",app.name).put("uid",app.uid)
            .put("version",d.version).put("disable",suspend).put("restore_enabled",restoreState).put("expires_at",System.currentTimeMillis()+120_000)
            .put("effect",if(suspend)"停用该普通应用，将中断其功能；不会卸载或清空数据" else "恢复此前记录的启用状态")
        plans.entries.removeIf {it.value.getLong("expires_at")<System.currentTimeMillis()}
        plans[token]=plan;ok().put("plan",plan)
    }
    private suspend fun apply(token: String, confirmation: String): JSONObject = scanMutex.withLock {
        val plan=plans.remove(token) ?: error("确认已过期或已使用")
        check(plan.getLong("expires_at")>=System.currentTimeMillis() && confirmation==plan.getString("package")){"确认内容不匹配或已过期"}
        check(!enabled()){"监测已重新启动，请暂停后重试"}
        check(bridge.shell(ShellCommands.ID).trim()=="2000"){"执行后端身份已改变"}
        val app=AuditJson.app(requireNotNull(store.app(plan.getString("key"))))
        val inventory=AuditCore.packages(bridge.shell(ShellCommands.packages(app.user)),app.user)
        check(inventory.any {it.key()==app.key() && it.uid==plan.getInt("uid")}){"应用身份已改变"}
        val d=AuditCore.detail(bridge.shell(ShellCommands.detail(app.name)),app.name,app.user)
        check(d.version==plan.getString("version") && d.enabled==(if(plan.getBoolean("disable"))plan.getInt("restore_enabled") else 3)){"应用版本或启用状态已改变"}
        AuditCore.allowControl(app,d,inventory.count {it.uid==app.uid},protectedPackages())
        val ops=AuditCore.appOps(bridge.shell(ShellCommands.ops(app)))
        check(ops.modes["ACTIVATE_VPN"]!="allow" && ops.modes["ESTABLISH_VPN_SERVICE"]!="allow"){"VPN 组件受保护"}
        check(AuditCore.uidState(bridge.shell(ShellCommands.state(app.uid)))>=4){"当前前台应用受保护"}
        store.event("control_prepared",plan)
        val managed=JSONObject(store.get("disables") ?: "{}")
        // Persist intent before executing: a transport timeout must not erase a possible side effect.
        managed.put(app.key(),JSONObject().put("state","pending").put("plan",plan));store.put("disables",managed.toString())
        try {
            val target=if(plan.getBoolean("disable"))3 else plan.getInt("restore_enabled")
            bridge.shell(ShellCommands.setEnabled(app,target))
            val after=AuditCore.detail(bridge.shell(ShellCommands.detail(app.name)),app.name,app.user)
            check(after.enabled==target){"系统未确认目标状态，请查看系统应用设置"}
            if(after.enabled==3)managed.put(app.key(),JSONObject().put("state","disabled").put("plan",plan)) else managed.remove(app.key())
            store.put("disables",managed.toString());store.event("control_verified",JSONObject().put("key",app.key()).put("enabled_state",after.enabled))
            val updated=requireNotNull(store.app(app.key())).put("detail",AuditJson.detail(after)).put("detail_at",System.currentTimeMillis());store.saveApp(updated)
            ok().put("enabled_state",after.enabled)
        }catch(e: Exception){store.event("control_unconfirmed",JSONObject().put("key",app.key()).put("error",e.message));throw e}
    }
    private suspend fun export() {
        val report=JSONObject().put("schema",1).put("version",VERSION).put("created_at",System.currentTimeMillis())
            .put("summary",store.get("summary")?.let(::JSONObject) ?: JSONObject.NULL).put("apps",JSONArray(store.apps()))
            .put("recent_history",store.history(200)).put("verification",store.verify()).put("limits",JSONArray(LIMITS))
            .put("export_scope","应用快照和最近 200 条历史；完整保留历史可分页读取")
        val encoded=android.util.Base64.encodeToString(report.toString(2).toByteArray(Charsets.UTF_8),android.util.Base64.NO_WRAP)
        check(encoded.length<=16_000_000){"报告过大，请用 history 分页读取"}
        val path="/sdcard/Download/AI-Limbs-Privacy-Audit-${System.currentTimeMillis()}.json"
        val temp="$path.partial"
        // Upload no data: this writes only to the user's on-device Downloads folder after confirmation.
        var completed=false
        try {
            bridge.shell("umask 077; : > ${ShellCommands.quote(temp)}",maxChars=2000)
            for(chunk in encoded.chunked(48_000))bridge.shell("printf %s ${ShellCommands.quote(chunk)} | base64 -d >> ${ShellCommands.quote(temp)}",maxChars=2000)
            bridge.shell("mv ${ShellCommands.quote(temp)} ${ShellCommands.quote(path)}",maxChars=2000)
            completed=true
            store.event("export",JSONObject().put("path",path).put("history_limit",200))
            store.put("export_result",JSONObject().put("state","complete").put("path",path).toString())
        } finally {
            if(!completed)withContext(NonCancellable) {
                try {bridge.shell("rm -f ${ShellCommands.quote(temp)}",maxChars=2000)}
                catch(e: Exception){store.event("export_partial_cleanup_failed",JSONObject().put("path",temp).put("error",e.message))}
            }
        }
    }
    private fun ok()=JSONObject().put("success",true)
    companion object {
        const val VERSION="0.1.0"
        val LIMITS=listOf(
            "监测优先级只决定采样顺序，不是危险等级；无启动入口不证明始终后台。",
            "本版复用现有 ADB/Shizuku Shell，不安装 Root、不占用 VPN、不修改 Clash、不解密通信。权限失效即报告无法观测。",
            "清单按用户枚举；安全文件夹、私密空间、工作资料、系统守护进程可能不可见。空数据不是已覆盖。",
            "默认每 15 分钟轮询，一级/二级/三级每轮最多 12/8/4 个深度样本，组内最久未采样者优先。可手动检查指定应用。",
            "仅插件挂载期间运行；宿主/Resident 停止、重启间隙、休眠调度延迟会形成空窗，不能保证实时或永久常驻。",
            "AppOps 是系统记录，权限授予不等于实际使用；历史访问时间不会被拼成当前后台访问。",
            "UID 流量为系统累计计数变化，桶可能延迟更新；排除 VPN 虚拟接口后仍有 VPN 转发、共享 UID 与 OEM 归因误差。",
            "R0 仅表示本轮未触发规则；UNKNOWN 表示证据不足。R2 是待核查的相关现象，不能证明未经授权收集或上传。",
            "历史存储在插件私有目录，默认 30 天、最多 5000 条；哈希链只检测保留区间内部一致性。导出由你主动确认。",
            "暂停监测不撤销系统应用限制。权限及后台限制可在系统设置中调整；临时停用/恢复仅针对受保护清单以外的主用户普通应用。"
        )
    }
}
