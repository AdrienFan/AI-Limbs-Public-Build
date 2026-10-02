package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Keep editable contours separate from the persisted, final eight-bit coverage. */
internal object ArtCurveSoftSelection {
    private fun copy(p:JSONObject)=JSONObject(p.toString())
    private fun plain(p:JSONObject)=copy(p).apply {remove("curveParts");remove("curveBasis")}
    fun validate(s:JSONObject) {
        require((s.optString("shape")=="raster" && s.has("coverage")) || (s.optString("shape")=="rect" && s.getDouble("width")==0.0 && s.getDouble("height")==0.0)) {"可编辑源轮廓需要统一软蒙版或空选区"}
        val parts=s.getJSONArray("curveParts")
        require(parts.length() in 1..ArtBezierSelection.MAX_PARTS)
        val basis=s.getJSONObject("curveBasis")
        for(k in listOf("x","y","width","height"))require(basis.getDouble(k).isFinite() && kotlin.math.abs(basis.getDouble(k))<=1000000)
        require(basis.getDouble("width")>0 && basis.getDouble("height")>0)
        var nodes=0;var pixels=0L;var runs=0
        for(i in 0 until parts.length()) {
            val part=parts.getJSONObject(i);val child=part.getJSONObject("selection")
            require(!child.has("curveParts") && child.optString("shape")!="compound")
            require(part.getString("mode") in if(i==0)setOf("replace") else ArtSoftSelection.modes.keys-setOf("replace"))
            ArtSelection.validate(child)
            if(child.optString("shape")=="bezier") {
                nodes+=child.getJSONArray("nodes").length();ArtSoftSelection.options(part.getJSONObject("options"))
            } else {require(!part.has("options"));nodes+=if(child.optString("shape")=="polygon")child.getJSONArray("vertices").length() else 4}
            if(child.optString("shape")=="raster") {pixels+=child.getInt("maskWidth").toLong()*child.getInt("maskHeight");runs+=child.getInt("runCount")}
        }
        require(nodes<=ArtBezierSelection.MAX_TOTAL_NODES && pixels<=ArtRasterSelection.MAX_PIXELS && runs<=ArtRasterSelection.MAX_RUNS) {"可编辑软选区历史超过预算，请替换选区"}
        require(parts.toString().length<=6*1024*1024) {"可编辑软选区历史过大，请替换选区"}
    }
    fun parts(s:JSONObject):MutableList<JSONObject> {
        if(!s.has("curveParts"))return ArtBezierSelection.parts(s).map {part->
            copy(part).apply {val child=getJSONObject("selection");if(child.optString("shape")=="bezier")put("options",ArtSoftSelection.defaults().put("antialias",0))}
        }.toMutableList()
        validate(s)
        val basis=ArtBezierSelection.frame(s.getJSONObject("curveBasis"));val now=ArtBezierSelection.frame(s)
        // Empty boolean results still retain their original nodes for subsequent editing.
        val transform=now.width()>0 && now.height()>0
        return (0 until s.getJSONArray("curveParts").length()).map {i->
            copy(s.getJSONArray("curveParts").getJSONObject(i)).apply {
                if(transform) {val child=getJSONObject("selection");val sx=now.width()/basis.width();val sy=now.height()/basis.height()
                    child.put("x",now.left+(child.getDouble("x")-basis.left)*sx).put("y",now.top+(child.getDouble("y")-basis.top)*sy)
                        .put("width",child.getDouble("width")*sx).put("height",child.getDouble("height")*sy)
                }
            }
        }.toMutableList()
    }
    private fun build(parts:List<JSONObject>,w:Int,h:Int):JSONObject {
        require(parts.size in 1..ArtBezierSelection.MAX_PARTS) {"复合选区最多32分量，请替换选区"}
        var result:JSONObject?=null
        for(part in parts) {
            val child=part.getJSONObject("selection")
            val mask=if(part.has("options"))ArtSoftSelection.process(child,part.getJSONObject("options"),w,h) else child
            result=ArtSoftSelection.combine(result,mask,part.getString("mode"),w,h)
        }
        val out=plain(requireNotNull(result));val b=ArtBezierSelection.frame(out)
        val basis=JSONObject().put("x",b.left).put("y",b.top).put("width",if(b.width()>0)b.width() else 1f)
            .put("height",if(b.height()>0)b.height() else 1f)
        out.put("curveParts",JSONArray(parts)).put("curveBasis",basis);validate(out)
        return out
    }
    fun create(current:JSONObject?,geometry:JSONObject,p:JSONObject,w:Int,h:Int):JSONObject {
        val o=ArtSoftSelection.options(p);val mode=o.getString("mode")
        val item=JSONObject().put("mode",mode).put("selection",geometry).put("options",o)
        val entries=if(mode=="replace" || (current==null && mode in setOf("add","xor")))mutableListOf<JSONObject>() else if(current!=null)parts(current) else mutableListOf(JSONObject().put("mode","replace").put("selection",ArtBezierSelection.empty()))
        if(entries.isEmpty())item.put("mode","replace")
        entries.add(item);return build(entries,w,h)
    }
    fun edit(s:JSONObject,index:Int,edits:JSONArray,w:Int,h:Int):JSONObject {
        val entries=parts(s);require(index in entries.indices)
        val part=entries[index];part.put("selection",ArtBezierSelection.edited(part.getJSONObject("selection"),0,edits))
        return build(entries,w,h)
    }
    fun append(current:JSONObject?,mask:JSONObject,mode:String,w:Int,h:Int):JSONObject {
        if(mode=="replace" || current?.has("curveParts")!=true)return ArtSoftSelection.combine(current,mask,mode,w,h)
        val entries=parts(current);entries.add(JSONObject().put("mode",mode).put("selection",plain(mask)))
        return build(entries,w,h)
    }
}
