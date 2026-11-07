package com.ai.limbs.plugins.privacyaudit

import com.ai.limbs.plugin.runtime.*
import org.json.JSONArray
import org.json.JSONObject

internal object AuditIds {
    const val PLUGIN="plugin.privacy_audit"
    const val PAGE="$PLUGIN.page"
    const val SCREEN="$PLUGIN.screen"
    const val TILE="$PLUGIN.tile"
    const val CONTROL="$PLUGIN.control"
}

class AuditEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val controller=AuditController(host)
        fun parameter(name:String,type:String="string",required:Boolean=false)=InProcessCapabilityParameterSpec(name,type,name,required)
        fun register(op:String,effect:InProcessCapabilityEffect,parameters:List<InProcessCapabilityParameterSpec>) {
            host.registerCapability(InProcessCapabilitySpec(
                id="${AuditIds.PLUGIN}.$op",displayName="隐私安全审计 · $op",
                description=when(op){
                    "status"->"读取分页应用、证据时间、监测优先级、风险与盲区；UNKNOWN 不是安全。"
                    "scan"->"发起一次本地审计，立即返回，不改变被审计应用。"
                    "inspect"->"排队检查一个已枚举应用的权限、AppOps、状态和 UID 流量。"
                    "configure"->"用户控制本地轮询的启停、间隔、历史保留及通知。"
                    "history"->"分页读取本地证据链。按 next_before 继续，不重新执行采样。"
                    "verify"->"校验保留历史的本地哈希链；不能证明对抗数据库写权限者的真实性。"
                    else->"用户操作：settings/expectation/acknowledge/plan_disable/plan_restore/apply_control/export/clear_history。停用仅能用预检生成的短时 token 和完整包名确认；禁止自动根据风险处置。"
                },parameters=parameters,effect=effect,domain=InProcessCapabilityDomain.PLUGIN,
                executor=InProcessCapabilityExecutor {raw->
                    val p=JSONObject(raw)
                    controller.command(if(op=="control")p.getString("operation") else op,p).toString()
                }
            ))
        }
        register("status",InProcessCapabilityEffect.READ_ONLY,listOf(parameter("query"),parameter("offset","integer"),parameter("limit","integer")))
        register("scan",InProcessCapabilityEffect.STATE_CHANGE,emptyList())
        register("inspect",InProcessCapabilityEffect.STATE_CHANGE,listOf(parameter("key",required=true)))
        register("configure",InProcessCapabilityEffect.PERSISTENT_WRITE,listOf(parameter("enabled","boolean"),parameter("interval_minutes","integer"),parameter("retention_days","integer"),parameter("notifications","boolean")))
        register("history",InProcessCapabilityEffect.READ_ONLY,listOf(parameter("limit","integer"),parameter("before","integer")))
        register("verify",InProcessCapabilityEffect.READ_ONLY,emptyList())
        register("control",InProcessCapabilityEffect.STATE_CHANGE,listOf(parameter("operation",required=true),parameter("key"),parameter("token"),parameter("confirm_package"),parameter("confirm","boolean"),parameter("expected_background","boolean")))
        host.registerProvider(AuditIds.PAGE,AuditPage(host) {op,p->controller.command(op,p)},mapOf("kind" to "plugin_page","screen_id" to AuditIds.SCREEN))
        host.registerScreen(InProcessScreen(id=AuditIds.SCREEN,title="隐私安全审计",description="按实际证据查看应用活动、风险与监测盲区。",
            schemaId="ai_limbs.plugin_center.ui.v1",documentJson=JSONObject().put("schema",1).put("layout","edge_to_edge")
                .put("blocks",JSONArray().put(JSONObject().put("type","plugin_page").put("provider_id",AuditIds.PAGE))).toString()))
        host.registerHomeTile(InProcessHomeTile(id=AuditIds.TILE,title="隐私安全审计",description="本地证据 · 独立风险 · 用户控制",screenId=AuditIds.SCREEN))
        controller.mount()
        return InProcessPluginHandle {controller.stop()}
    }
}

class AuditPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(host: InProcessPluginPresentationHost): InProcessPluginPresentationHandle {
        val page=AuditPage(host) {op,p->
            val direct=op in setOf("status","scan","inspect","configure","history","verify")
            val id=if(direct)"${AuditIds.PLUGIN}.$op" else AuditIds.CONTROL
            if(!direct)p.put("operation",op)
            val response=JSONObject(host.invokePluginCapability(id,p.toString()))
            check(response.optBoolean("success",true)){response.optString("error","操作失败")}
            response
        }
        val registration=host.registerPageProvider(AuditIds.PAGE,page,mapOf("kind" to "plugin_page","screen_id" to AuditIds.SCREEN))
        return InProcessPluginPresentationHandle {registration.close()}
    }
}
