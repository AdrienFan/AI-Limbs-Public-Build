package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Own sampled inertia stage; the resulting positions enter the shared freehand engine once. */
internal object ArtDyna {
    private const val EPS=0.000001
    fun settings(stroke:JSONObject):JSONObject {
        val p=JSONObject(stroke.toString())
        val mass=p.optDouble("mass",0.5);val drag=p.optDouble("drag",0.15)
        require(mass.isFinite() && mass in 0.0..1.0 && drag.isFinite() && drag in 0.0..1.0) {"Mass/Drag须为0–1"}
        val tool=p.optString("brushTool","ink");require(tool in ArtBrush.tools)
        return p.put("mass",mass).put("drag",drag).put("brushTool",tool)
    }
    class Filter(mass:Double,drag:Double) {
        private val weight=1+159*mass
        private val damping=0.5*drag*drag
        private var cursor:ArtBrush.Sample?=null
        private var vx=0.0
        private var vy=0.0
        init {require(mass.isFinite() && mass in 0.0..1.0 && drag.isFinite() && drag in 0.0..1.0)}
        fun accept(sample:ArtBrush.Sample):ArtBrush.Sample {
            val previous=cursor
            if(previous==null) {cursor=sample;return sample}
            val fx=sample.x-previous.x;val fy=sample.y-previous.y
            var x=previous.x;var y=previous.y
            if(hypot(fx,fy)>=EPS) {
                vx+=fx/weight;vy+=fy/weight
                if(hypot(vx,vy)>=EPS) {
                    vx*=1-damping;vy*=1-damping;x+=vx;y+=vy
                }
            }
            require(x.isFinite() && y.isFinite()) {"动态轨迹超出计算范围"}
            // Stationary positions still retain pressure, time and tilt for shared brush dynamics/airbrush.
            return sample.copy(x=x,y=y).also {cursor=it}
        }
    }
    fun normalize(stroke:JSONObject,state:JSONObject):JSONObject {
        require(stroke.getString("tool")=="dyna")
        val p=settings(stroke);val raw=p.getJSONArray("points");val filter=Filter(p.getDouble("mass"),p.getDouble("drag"))
        var output=ArtBrush.samples(raw).map(filter::accept)
        if(p.has("assistantId")) {
            val id=p.getString("assistantId")
            val guide=ArtAssistants.items(state).firstOrNull {it.getString("id")==id} ?: error("尺规不存在")
            require(guide.getBoolean("visible") && guide.getBoolean("enabled")) {"尺规隐藏或吸附已禁用"}
            val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
            val toDocument=ArtShapes.layerMatrix(state,layer);val inverse=Matrix();require(toDocument.invert(inverse))
            fun mapped(sample:ArtBrush.Sample,matrix:Matrix):ArtBrush.Sample {
                val point=floatArrayOf(sample.x.toFloat(),sample.y.toFloat());matrix.mapPoints(point)
                return sample.copy(x=point[0].toDouble(),y=point[1].toDouble())
            }
            val document=output.map {mapped(it,toDocument)}
            val start=document.first();val projection=ArtAssistants.Projection(guide,AssistantPoint(start.x,start.y))
            output=document.map {sample ->
                val next=projection.project(AssistantPoint(sample.x,sample.y));mapped(sample.copy(x=next.x,y=next.y),inverse)
            }
        }
        val points=JSONArray(output.map {it.json()})
        ArtBrush.samples(points)
        return p.put("dynaVersion",1).put("dynaInput",JSONArray(raw.toString()))
            .put("dynaPoints",JSONArray(points.toString())).put("points",points)
    }
    fun validateStored(stroke:JSONObject) {
        require(stroke.getInt("dynaVersion")==1)
        settings(stroke)
        val input=stroke.getJSONArray("dynaInput");val output=stroke.getJSONArray("dynaPoints")
        require(input.length()==output.length()) {"动态轨迹采样数不一致"}
        ArtBrush.samples(input);ArtBrush.samples(output)
    }
    fun info()=JSONObject().put("tool","dyna").put("brushTools",JSONObject(ArtBrush.tools))
        .put("defaults",JSONObject().put("mass",0.5).put("drag",0.15).put("brushTool","ink"))
        .put("ranges",JSONObject().put("mass",JSONArray().put(0).put(1)).put("drag",JSONArray().put(0).put(1)))
        .put("mapping","质量=1+159×Mass；阻尼=0.5×Drag²；逐采样积分，保留现有Mass/Drag含义")
        .put("pipeline","惯性过滤 → 可选尺规吸附 → 共享平滑/稳定器 → 笔刷印章；最终轨迹仅保存前计算一次")
        .put("controls","笔尖/纹理/动态曲线/预设/流量/间距/持续喷绘/平滑和稳定器均使用当前六支栅格笔配置；详见brush.info(tool=brushTool)")
        .put("coordinates","dyna.stroke及stroke.add为图层局部点；assistant.stroke为文档坐标点，tool=dyna；支持压力/时间/倾斜/方向")
        .put("endpoint","不强行追到原始指针抬笔位置；共享平滑的finish仅补到惯性轨迹终点")
        .put("limits",JSONObject().put("samples",ArtBrush.MAX_SAMPLES).put("dabs",ArtBrush.MAX_DABS).put("particles",ArtBrush.MAX_PARTICLES))
}
