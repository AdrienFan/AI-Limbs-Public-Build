package com.ai.limbs.plugins.privacyaudit

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal class AuditBridge(private val host: InProcessPluginHost) {
    suspend fun shell(command: String, screen: Boolean = false, maxChars: Int = 180_000): String = withTimeout(28_000) {
        val response=JSONObject(host.invokeHostCapability("host.process@1",JSONObject().put("operation","execute")
            .put("command",ShellCommands.wrap(command)).put("screen_action",screen).put("screen_feedback",false).toString()))
        check(response.optBoolean("success")) {response.optString("error","宿主拒绝执行；请检查权限服务与插件 scope")}
        val result=response.getJSONObject("structured_result")
        check(result.getInt("exitCode")==0) {"系统查询失败（${result.getInt("exitCode")}）：${result.optString("output").take(500)}"}
        val output=result.getString("output")
        check(output.length<=maxChars) {"结果超过采样上限，未将截断数据视为完整证据"}
        output
    }
    suspend fun attention(count: Int) {
        val root=JSONObject().put("operation",if(count>0)"publish" else "clear")
        if(count>0) root.put("label","隐私安全审计").put("groups",org.json.JSONArray().put(JSONObject().put("id","audit_alerts")
            .put("label","待核查证据").put("items",org.json.JSONArray().put(JSONObject().put("id","review")
                .put("label","有隐私审计异常需核查；不代表已证实窃密").put("count",count).put("semantic_tone","warning")))))
        val result=JSONObject(host.invokeHostCapability("host.attention@1",root.toString()))
        check(result.optBoolean("success",true)) {result.optString("error","告警发布失败")}
    }
}
