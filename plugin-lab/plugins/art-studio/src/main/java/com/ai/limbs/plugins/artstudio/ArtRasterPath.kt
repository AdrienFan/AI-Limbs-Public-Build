package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import android.graphics.PathMeasure
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Original controls remain separate from the arc-length brush samples used by immutable history. */
internal object ArtRasterPath {
    val tools=setOf("polygon","polyline","bezier")
    fun defaults()=JSONObject().put("outline","brush").put("brushTool","ink").put("figureFill",JSONObject().put("mode","none"))
    fun settings(p:JSONObject):JSONObject {
        val tool=p.getString("tool");require(tool in tools)
        val out=JSONObject(p.toString())
        val outline=if(p.has("outline"))p.getString("outline") else "brush";require(outline in ArtFigure.outlines)
        val brush=if(p.has("brushTool"))p.getString("brushTool") else "ink";require(brush in ArtBrush.tools)
        val foreground=if(p.has("color"))p.getString("color") else "#FF161616"
        require(foreground.matches(Regex("#[A-Fa-f0-9]{8}")))
        val fill=if(p.has("figureFill"))p.getJSONObject("figureFill") else JSONObject().put("mode",if(p.optBoolean("fillShape",false))"solid" else "none")
        val normalized=ArtFigure.fill(fill,foreground)
        require(tool=="polygon" || normalized.getString("mode")=="none") {"折线和栅格贝塞尔只描边，不填充"}
        require(outline!="none" || normalized.getString("mode")!="none") {"描边与填充不能同时为空"}
        out.put("outline",outline).put("brushTool",brush).put("figureFill",normalized).remove("fillShape")
        return out
    }
    private fun controls(tool:String,raw:JSONArray):JSONArray {
        require(when(tool) {"polygon"->raw.length() in 3..2048;"polyline"->raw.length() in 2..2048;"bezier"->raw.length() in 4..1024&&(raw.length()-1)%3==0;else->false}) {"多边形3–2048点；折线2–2048点；三次曲线为起点+每段两个控制点和终点，4–1024点"}
        require((0 until raw.length()).all {raw.getJSONArray(it).length() in 2..3}) {"路径控制点使用[x,y]，不接受自由笔轨迹时间"}
        return JSONArray(ArtBrush.samples(raw).map {JSONArray().put(it.x).put(it.y)})
    }
    fun path(tool:String,raw:JSONArray)=Path().apply {
        val first=raw.getJSONArray(0);moveTo(first.getDouble(0).toFloat(),first.getDouble(1).toFloat())
        if(tool=="bezier") {
            for(i in 1 until raw.length() step 3) {
                val a=raw.getJSONArray(i);val b=raw.getJSONArray(i+1);val c=raw.getJSONArray(i+2)
                cubicTo(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),b.getDouble(0).toFloat(),b.getDouble(1).toFloat(),c.getDouble(0).toFloat(),c.getDouble(1).toFloat())
            }
        } else {
            for(i in 1 until raw.length()) {val p=raw.getJSONArray(i);lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())}
            if(tool=="polygon")close()
        }
    }
    fun path(p:JSONObject)=path(p.getString("tool"),p.getJSONArray("pathInput"))
    fun normalize(p:JSONObject):JSONObject {
        val out=settings(p);val tool=out.getString("tool");val raw=controls(tool,p.getJSONArray("points"))
        val length=PathMeasure(path(tool,raw),tool=="polygon").length
        require(length.isFinite()&&length>0) {"路径长度必须大于0"}
        out.put("pathVersion",1).put("pathInput",raw).put("points",JSONArray(raw.toString()))
        return out
    }
    fun outlinePoints(p:JSONObject):JSONArray {
        val raw=p.getJSONArray("pathInput");val tool=p.getString("tool");val out=JSONArray()
        fun emit(x:Double,y:Double) {
            require(out.length()<ArtBrush.MAX_SAMPLES) {"路径笔刷描边超过10000采样，请缩短路径或选择普通描边"}
            out.put(JSONArray().put(x).put(y).put(1).put(out.length()*16).put(0).put(0))
        }
        val first=raw.getJSONArray(0);emit(first.getDouble(0),first.getDouble(1))
        if(tool=="bezier") {
            for(i in 1 until raw.length() step 3) {
                val segment=JSONArray().put(raw.getJSONArray(i-1)).put(raw.getJSONArray(i)).put(raw.getJSONArray(i+1)).put(raw.getJSONArray(i+2))
                val measure=PathMeasure(path("bezier",segment),false);val length=measure.length.toDouble()
                val count=ceil(length/0.75).toInt().coerceAtLeast(1);require(count<ArtBrush.MAX_SAMPLES && out.length()+count<=ArtBrush.MAX_SAMPLES)
                val position=FloatArray(2)
                if(length>0)for(j in 1..count) {
                    require(measure.getPosTan((length*j/count).toFloat(),position,null));emit(position[0].toDouble(),position[1].toDouble())
                }
                val end=raw.getJSONArray(i+2);val last=out.getJSONArray(out.length()-1);last.put(0,end.getDouble(0)).put(1,end.getDouble(1))
            }
        } else {
            val count=raw.length()+(if(tool=="polygon")1 else 0)
            for(i in 1 until count) {
                val a=raw.getJSONArray(i-1);val b=raw.getJSONArray(i%raw.length());val dx=b.getDouble(0)-a.getDouble(0);val dy=b.getDouble(1)-a.getDouble(1)
                val length=hypot(dx,dy);if(length==0.0)continue
                val n=ceil(length/0.75).toInt();require(n<ArtBrush.MAX_SAMPLES && out.length()+n<=ArtBrush.MAX_SAMPLES)
                for(j in 1..n)emit(a.getDouble(0)+dx*j/n,a.getDouble(1)+dy*j/n)
            }
        }
        require(out.length()>=2);return out
    }
    fun validateStored(p:JSONObject) {
        require(p.getInt("pathVersion")==1);settings(p);controls(p.getString("tool"),p.getJSONArray("pathInput"))
        require((p.getString("outline")=="brush")==p.has("brush"))
    }
    fun info()=JSONObject().put("tools",JSONArray(tools.toList())).put("defaults",defaults())
        .put("coordinates","Layer-local controls; polygon closes automatically, polyline stays open; bezier is anchor + repeated control1/control2/end triples")
        .put("outline","Shared six dab-v1 brushes/presets/tips/textures/dynamics; fixed pressure=1, synthetic arc-length samples, smoothing/timed airbrush disabled except pixel_perfect")
        .put("fill","Polygon only: figureFill none/solid/pattern, checker/stripes/dots/image; same RGBA tile resources and transforms as figure.draw")
        .put("limits","Polygon/polyline <=2048 controls; cubic <=1024 controls; brush samples <=10000; degenerate/over-budget paths rejected")
}
