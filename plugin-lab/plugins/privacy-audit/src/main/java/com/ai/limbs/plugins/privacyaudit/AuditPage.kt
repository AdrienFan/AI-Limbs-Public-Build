package com.ai.limbs.plugins.privacyaudit

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Host and Resident share one UI; only the business owner holds state or starts sampling. */
internal class AuditPage(private val host: InProcessPluginUiHost, private val call: suspend (String,JSONObject)->JSONObject): InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View = Page(context).root
    private inner class Page(private val context: Context) {
        val root=ScrollView(context)
        private val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(30))}
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        private val dark=(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
        private val ink=if(dark)Color.rgb(230,237,240) else Color.rgb(29,49,61)
        private val muted=if(dark)Color.rgb(165,183,194) else Color.rgb(91,112,124)
        private val card=if(dark)Color.rgb(29,43,51) else Color.rgb(238,244,246)
        private val stage=text("尚未采样",16)
        private val summary=text("",14)
        private val list=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        private val search=EditText(context).apply {hint="搜索包名";setSingleLine(true);setTextColor(ink);setHintTextColor(muted)}
        private var data=JSONObject()
        private var offset=0
        private var refreshJob:Job?=null
        init {
            root.setBackgroundColor(if(dark)Color.rgb(16,26,33) else Color.WHITE)
            root.addView(content)
            content.addView(text("隐私安全审计",25,true))
            content.addView(text("本地证据 · 独立风险 · 用户控制",13).apply{setTextColor(muted)})
            content.addView(stage);content.addView(summary)
            row(button("立即扫描"){call("scan",JSONObject());refresh()},button("刷新"){refresh()})
            row(button("开启连续监测"){confirm("开启本地连续监测","默认每 15 分钟采样，采集应用清单、权限、系统访问记录和流量计数。只在宿主运行且权限后端可用时有效。") {call("configure",JSONObject().put("enabled",true));refresh()}},
                button("暂停监测"){call("configure",JSONObject().put("enabled",false));refresh()})
            row(button("采样与通知"){configuration()},button("覆盖与盲区"){showText("覆盖与盲区",coverageText())})
            row(button("审计历史"){history(null)},button("校验历史"){showText("保留区间校验",call("verify",JSONObject()).toString(2))})
            row(button("导出报告"){confirm("导出到手机下载目录","报告包含应用清单和最近 200 条审计历史。下载目录中的文件可能被其他应用读取；不会上传云端。") {
                showText("导出已排队",call("export",JSONObject().put("confirm",true)).getString("message"))}},
                button("确认已读告警"){call("acknowledge",JSONObject());refresh()})
            content.addView(search)
            row(button("搜索"){offset=0;refresh()},button("上一页"){offset=(offset-30).coerceAtLeast(0);refresh()},button("下一页"){if(offset+30<data.optInt("total")){offset+=30;refresh()}})
            content.addView(text("优先级 ≠ 危险程度",17,true))
            content.addView(text("一级优先采样无启动入口组件；风险由单独的证据规则决定。UNKNOWN 表示证据不足。",13).apply{setTextColor(muted)})
            content.addView(list)
            root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v:View) {
                    refreshJob=scope.launch {while(isActive){safely{refresh()};delay(if(data.optBoolean("busy"))3000 else 15000)}}
                }
                override fun onViewDetachedFromWindow(v:View) {refreshJob?.cancel();scope.cancel()}
            })
        }
        private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
        private fun text(value:String,size:Int,bold:Boolean=false)=TextView(context).apply {
            text=value;textSize=size.toFloat();setTextColor(ink);setPadding(0,dp(5),0,dp(5));setTextIsSelectable(true)
            if(bold)setTypeface(typeface,Typeface.BOLD)
        }
        private fun button(label:String,action:suspend ()->Unit)=Button(context).apply {
            text=label;setAllCaps(false);minHeight=dp(44);setOnClickListener{scope.launch{safely{action()}}}
        }
        private suspend fun safely(action:suspend ()->Unit) {
            try{action()}catch(e:CancellationException){throw e}catch(e:Exception){host.logger.w("PrivacyAuditUI","Action failed: ${e.message}",e);showText("操作未完成",e.message ?: "未知错误")}
        }
        private fun row(vararg views:View) {
            val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
            views.forEach{row.addView(it,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))};content.addView(row)
        }
        private suspend fun refresh() {
            data=call("status",JSONObject().put("query",search.text.toString()).put("offset",offset).put("limit",30))
            stage.text=(if(data.optBoolean("enabled"))"● 连续监测开启" else "○ 连续监测暂停")+"  ·  "+data.optString("progress")
            val s=data.optJSONObject("summary")
            summary.text="最近完成：${stamp(s?.optLong("at") ?: 0)}\n可见应用 ${s?.optInt("visible_packages") ?: 0} · 已有详情样本 ${data.optInt("deep_samples")} · 每 ${data.optInt("interval_minutes")} 分钟\n"+
                "详情按组轮询，未采样及过期证据请单独核查。"+if(data.optString("last_error").isNotBlank())"\n${data.getString("last_error")}" else ""
            data.optJSONObject("export_result")?.let {e->summary.append("\n导出：${e.optString("state")} ${e.optString("path")} ${e.optString("error")}")}
            list.removeAllViews()
            val apps=data.optJSONArray("apps") ?: JSONArray()
            if(apps.length()==0)list.addView(text("暂无匹配记录。首次扫描后展示可见应用；没有记录不代表没有风险。",14))
            for(i in 0 until apps.length()) {
                val a=apps.getJSONObject(i)
                val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(10));background=GradientDrawable().apply{setColor(card);setCornerRadius(dp(12).toFloat())}}
                box.addView(text("${a.optString("risk","UNKNOWN")}  ·  ${a.optInt("priority",2)} 级监测  ·  用户 ${a.getInt("user")}",14,true))
                box.addView(text(a.getString("package"),15,true))
                box.addView(text("${a.optString("classification")}\n详情：${stamp(a.optLong("detail_at"))} · 风险评估：${stamp(a.optLong("risk_at"))}",12).apply{setTextColor(muted)})
                box.addView(button("证据与控制"){details(a)})
                list.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
            }
        }
        private fun stamp(time:Long)=if(time<=0)"尚未采样" else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(time))
        private fun details(a:JSONObject) {
            val key=a.getString("key")
            val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(16))}
            val reasons=a.optJSONArray("reasons") ?: JSONArray().put("尚未取得足够证据")
            val evidence=buildString {
                append("用户 ${a.getInt("user")} / UID ${a.getInt("uid")}\n${a.optString("classification")}\n\n")
                append("风险：${a.optString("risk","UNKNOWN")}（${stamp(a.optLong("risk_at"))}）\n")
                append("采样运行状态：${stateText(a)}\n")
                for(i in 0 until reasons.length())append("• ${reasons.getString(i)}\n")
                append("\n发送计数新增：${if(a.isNull("tx_added"))"未知/建立基线" else a.getLong("tx_added").toString()+" B"}")
                append("\n后台发送计数新增：${if(a.isNull("bg_tx_added"))"未知/建立基线" else a.getLong("bg_tx_added").toString()+" B"}")
                append("\n${a.optString("network_status")}\n${a.optString("coverage")}\n")
                append("\n系统访问证据：\n${a.optJSONArray("appops_evidence")?.toString(2) ?: "无法观测"}\n")
                append("\n权限及安装详情（${stamp(a.optLong("detail_at"))}）：\n${a.optJSONObject("detail")?.toString(2) ?: "尚未采样"}\n")
                append("\n采样缺口：${a.optJSONArray("sample_errors")?.toString(2) ?: "尚未采样"}")
            }
            body.addView(text(evidence,13))
            val dialog=AlertDialog.Builder(context).setTitle(a.getString("package")).setView(ScrollView(context).apply{addView(body)}).setNegativeButton("关闭",null).create()
            body.addView(button("重新采样此应用"){call("inspect",JSONObject().put("key",key));dialog.dismiss();refresh()})
            body.addView(button("权限与后台限制：打开系统设置"){call("settings",JSONObject().put("key",key))})
            body.addView(button(if(a.optBoolean("expected_background"))"取消预期后台标记" else "标记为预期后台活动") {
                call("expectation",JSONObject().put("key",key).put("expected_background",!a.optBoolean("expected_background")));dialog.dismiss();refresh()
            })
            body.addView(button("临时停用（需先暂停监测）"){control(key,true)})
            if(data.optJSONObject("managed_disables")?.has(key)==true)body.addView(button("恢复本插件停用的应用"){control(key,false)})
            dialog.show()
        }
        private suspend fun control(key:String,suspend:Boolean) {
            val plan=call(if(suspend)"plan_disable" else "plan_restore",JSONObject().put("key",key)).getJSONObject("plan")
            confirm("确认${if(suspend)"临时停用" else "恢复"}","${plan.getString("package")}\n${plan.getString("effect")}\n确认两分钟内有效。") {
                call("apply_control",JSONObject().put("token",plan.getString("token")).put("confirm_package",plan.getString("package")));refresh()
            }
        }
        private fun configuration() {
            val choices=arrayOf("每 5 分钟","每 15 分钟","每 30 分钟","每 60 分钟","保留 7 天","保留 30 天","保留 90 天",if(data.optBoolean("notifications"))"关闭系统通知" else "开启系统通知（受宿主授权控制）")
            AlertDialog.Builder(context).setTitle("本地监测设置").setItems(choices){_,i->scope.launch{safely{
                val p=when(i){0,1,2,3->JSONObject().put("interval_minutes",listOf(5,15,30,60)[i]);4,5,6->JSONObject().put("retention_days",listOf(7,30,90)[i-4]);else->JSONObject().put("notifications",!data.optBoolean("notifications"))}
                call("configure",p);refresh()
            }}}.setNegativeButton("取消",null).show()
        }
        private fun stateText(a:JSONObject):String {
            if(a.isNull("uid_state"))return "无法观测"
            val state=a.getInt("uid_state")
            return when(state){0,1->"系统常驻";2,3->"前台界面";4,5->"前台服务";6->"重要前台进程";in 7..11->"后台进程/服务";in 12..19->"休眠或缓存状态";20->"未运行";else->"未知"}+"（UID 状态 $state，仅该采样时刻）"
        }
        private fun coverageText():String=buildString {
            val s=data.optJSONObject("summary")
            if(s==null)append("尚无采样结果") else {
                append("完成时间：${stamp(s.optLong("at"))}\n")
                val users=s.optJSONArray("users") ?: JSONArray()
                for(i in 0 until users.length()){
                    val u=users.getJSONObject(i)
                    append("用户 ${u.getInt("user")}：")
                    if(u.optString("status")=="enumerated")append("可见 ${u.optInt("packages")} 个包\n") else append("无法观测 · ${u.optString("reason")}\n")
                }
                append("可见系统 Binder 服务：${s.optJSONArray("visible_binder_services")?.length() ?: "未知"}\n")
                append("网络采样：${if(s.optBoolean("network_observable"))"取得 UID 统计" else "不可观测"}\n")
                append("本轮缺口：${s.optJSONArray("errors")?.toString(2) ?: "未知"}\n")
            }
            append("\n");append(AuditController.LIMITS.joinToString("\n\n"))
        }
        private suspend fun history(before:Long?) {
            val p=JSONObject().put("limit",30);if(before!=null)p.put("before",before)
            val h=call("history",p).getJSONObject("history")
            val view=text(h.toString(2),12).apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
            AlertDialog.Builder(context).setTitle("本地审计历史").setView(ScrollView(context).apply{addView(view)})
                .setPositiveButton("更早记录"){_,_->if(!h.isNull("next_before"))scope.launch{safely{history(h.getLong("next_before"))}}}
                .setNeutralButton("清除历史"){_,_->confirm("清除保留历史","历史证据将永久删除。当前应用快照与停用恢复记录仍保留。"){call("clear_history",JSONObject().put("confirm",true))}}
                .setNegativeButton("关闭",null).show()
        }
        private fun showText(title:String,value:String) {
            AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply{addView(text(value,13).apply{setPadding(dp(16),dp(8),dp(16),dp(8))})}).setPositiveButton("关闭",null).show()
        }
        private fun confirm(title:String,message:String,action:suspend ()->Unit) {
            AlertDialog.Builder(context).setTitle(title).setMessage(message).setNegativeButton("取消",null)
                .setPositiveButton("确认"){_,_->scope.launch{safely{action()}}}.show()
        }
    }
}
