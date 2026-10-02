package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Plugin-owned move geometry and units. No layer pick is inferred from an object bounding box. */
internal object ArtMove {
    val units=listOf("px","mm","cm","in","pt")
    fun defaults()=JSONObject().put("revision",0).put("layerMode","current").put("moveScope","auto")
        .put("unit","px").put("ppi",72.0).put("step",1.0).put("largeMultiplier",10.0)
        .put("alphaThreshold",1).put("ignoreLocked",false)
    fun validateSettings(p:JSONObject):JSONObject {
        require(p.getLong("revision")>=0)
        require(p.getString("layerMode") in setOf("current","content","group"))
        require(p.getString("moveScope") in setOf("auto","layer","selection"))
        require(p.getString("unit") in units)
        val ppi=p.getDouble("ppi");require(ppi.isFinite() && ppi in 1.0..2400.0)
        val step=p.getDouble("step");require(step.isFinite() && step in 0.0001..16384.0)
        require(toPixels(step,p.getString("unit"),ppi) in 0.0001..16384.0) {"键盘基础步进须在0.0001–16384文档像素内"}
        val factor=p.getDouble("largeMultiplier");require(factor.isFinite() && factor in 1.0..100.0)
        val threshold=p.get("alphaThreshold");require(threshold is Number && threshold.toDouble()%1==0.0 && threshold.toDouble() in 1.0..255.0)
        require(p.get("ignoreLocked") is Boolean)
        return p
    }
    fun settings(base:JSONObject,p:JSONObject):JSONObject = validateSettings(JSONObject(base.toString()).apply {
        for(key in listOf("layerMode","moveScope","unit","ppi","step","largeMultiplier","alphaThreshold","ignoreLocked"))if(p.has(key))put(key,p.get(key))
    })
    fun factor(unit:String,ppi:Double):Double {
        require(ppi.isFinite() && ppi in 1.0..2400.0)
        return when(unit) {"px"->1.0;"mm"->ppi/25.4;"cm"->ppi/2.54;"in"->ppi;"pt"->ppi/72.0;else->error("移动单位须为px/mm/cm/in/pt")}
    }
    fun toPixels(value:Double,unit:String,ppi:Double):Double {
        require(value.isFinite());val result=value*factor(unit,ppi);require(result.isFinite());return result
    }
    fun fromPixels(value:Double,unit:String,ppi:Double)=value/factor(unit,ppi)
    fun delta(p:JSONObject,o:JSONObject,selection:Boolean):Pair<Double,Double> {
        val unit=p.optString("unit",o.getString("unit"));val ppi=p.optDouble("ppi",o.getDouble("ppi"))
        var x=toPixels(p.getDouble("dx"),unit,ppi);var y=toPixels(p.getDouble("dy"),unit,ppi)
        require(abs(x)<=16384 && abs(y)<=16384) {"单次位移每轴最多16384文档像素"}
        if(selection) {x=x.roundToInt().toDouble();y=y.roundToInt().toDouble()}
        return x to y
    }
    fun nudge(direction:String,large:Boolean,o:JSONObject):Pair<Double,Double> {
        validateSettings(o)
        val pixels=toPixels(o.getDouble("step"),o.getString("unit"),o.getDouble("ppi"))*(if(large)o.getDouble("largeMultiplier") else 1.0)
        require(pixels<=16384) {"放大后的键盘步进超过16384文档像素"}
        val step=pixels.roundToInt().coerceAtLeast(1).toDouble()
        return when(direction) {"left"->-step to 0.0;"right"->step to 0.0;"up"->0.0 to -step;"down"->0.0 to step;else->error("方向须为left/right/up/down")}
    }
    fun hasSelection(state:JSONObject)=state.optJSONObject("selection")?.let {it.getDouble("width")>0 && it.getDouble("height")>0}==true
    fun selectionMode(state:JSONObject,scope:String):Boolean=when(scope) {
        "auto"->hasSelection(state);"layer"->false;"selection"->{require(hasSelection(state)) {"请先创建非空选区"};true};else->error("未知移动范围")
    }
    fun shiftedSelection(s:JSONObject,dx:Double,dy:Double):JSONObject=JSONObject(s.toString()).apply {
        // All supported contours are normalized to their frame; curveBasis remains the immutable source frame.
        put("x",getDouble("x")+dx);put("y",getDouble("y")+dy);ArtSelection.validate(this)
        require(abs(getDouble("x"))<=1000000 && abs(getDouble("y"))<=1000000 &&
            abs(getDouble("x")+getDouble("width"))<=1000000 && abs(getDouble("y")+getDouble("height"))<=1000000)
    }
    fun bounds(s:JSONObject):Rect {
        ArtSelection.validate(s)
        val x=s.getDouble("x");val y=s.getDouble("y");val w=s.getDouble("width");val h=s.getDouble("height")
        require(listOf(x,y,x+w,y+h).all {it.isFinite()&&abs(it)<=1000000})
        val rect=Rect(floor(x).toInt(),floor(y).toInt(),ceil(x+w).toInt(),ceil(y+h).toInt())
        require(!rect.isEmpty && rect.width() in 1..16384 && rect.height() in 1..16384 && rect.width().toLong()*rect.height()<=ArtRasterSelection.MAX_PIXELS) {"选区搬移范围最多4194304像素，边长最多16384"}
        return rect
    }
    fun visibility(state:JSONObject,layer:JSONObject):Double {
        val all=ArtMenuOperations.layers(state).associateBy {it.getString("id")};var node=layer;var opacity=1.0
        repeat(all.size+1) {
            if(!node.getBoolean("visible"))return 0.0
            opacity*=node.getDouble("opacity")
            val parent=node.optString("parentId");if(parent.isBlank())return opacity
            node=all.getValue(parent)
        }
        error("图层组存在循环引用")
    }
    fun drawOrder(state:JSONObject):List<JSONObject> {
        val all=ArtMenuOperations.layers(state);val out=mutableListOf<JSONObject>();val visited=mutableSetOf<String>()
        fun children(parent:String,depth:Int) {
            require(depth<=all.size)
            for(layer in all.filter {it.optString("parentId")==parent}) {
                require(visited.add(layer.getString("id")))
                if(layer.getString("kind")=="group")children(layer.getString("id"),depth+1)
                else out.add(layer)
            }
        }
        children("",0);require(visited.size==all.size) {"图层组结构不完整"};return out
    }
    fun pickGroup(state:JSONObject,layer:JSONObject):JSONObject {
        val parent=layer.optString("parentId")
        return if(parent.isBlank())layer else ArtMenuOperations.layers(state).first {it.getString("id")==parent}
    }
    /** An isolated document-space window includes source outside the current canvas, without persistent resizing. */
    fun window(snapshot:JSONObject,layerId:String,r:Rect):JSONObject {
        val isolated=ArtColorSampler.isolate(snapshot,layerId);val state=isolated.getJSONObject("state")
        state.put("width",r.width()).put("height",r.height())
        for(layer in ArtMenuOperations.layers(state))if(layer.optString("parentId").isBlank()) {
            layer.put("x",layer.getDouble("x")-r.left);layer.put("y",layer.getDouble("y")-r.top)
        }
        return isolated
    }
    fun hit(store:ArtStore,snapshot:JSONObject,x:Int,y:Int,o:JSONObject):JSONObject {
        validateSettings(o);val state=snapshot.getJSONObject("state")
        require(x in 0 until state.getInt("width") && y in 0 until state.getInt("height")) {"拾取点须在文档画布内"}
        val candidates=drawOrder(state).asReversed().filter {visibility(state,it)>0}
        require(candidates.size<=128) {"内容拾取最多128个可见内容层，请减少图层"}
        val logicalSize=state.getInt("width") to state.getInt("height")
        for(layer in candidates) {
            if(o.getBoolean("ignoreLocked") && ArtMenuOperations.isLocked(state,layer))continue
            val view=window(snapshot,layer.getString("id"),Rect(x,y,x+1,y+1))
            val originals=ArtMenuOperations.layers(state).associateBy {it.getString("id")}
            // Pixel extraction neutralizes composition; picking restores opacity so Android's alpha quantization is real.
            for(node in ArtMenuOperations.layers(view.getJSONObject("state")))
                node.put("opacity",originals.getValue(node.getString("id")).getDouble("opacity"))
            val pixel=ArtRenderer.render(store,view,logicalSize=logicalSize)
            val alpha=try {Color.alpha(pixel.getPixel(0,0))} finally {pixel.recycle()}
            val coverage=alpha
            if(coverage>=o.getInt("alphaThreshold")) {
                val target=if(o.getString("layerMode")=="group")pickGroup(state,layer) else layer
                return JSONObject().put("hit",true).put("layerId",target.getString("id")).put("contentLayerId",layer.getString("id"))
                    .put("coverage",coverage).put("editable",!ArtMenuOperations.isLocked(state,target)).put("name",target.getString("name"))
            }
        }
        return JSONObject().put("hit",false).put("layerId",JSONObject.NULL)
    }
    fun info()=JSONObject().put("settings",defaults()).put("units",JSONArray(units))
        .put("selectionLayers",JSONArray(listOf("paint","image"))).put("selectionPipeline","8-bit document projection × coverage; erase source by coverage, source-over at translated position; layer transform and all other content retained")
        .put("selectionFrame","selection moves with pixels, including editable normalized curve contours; integer document-pixel displacement")
        .put("pick","actual 1-pixel layer projection, visual draw order, ancestor visibility/opacity/clip; group=nearest parent; locked hit blocks unless ignoreLocked=true; no reference/assistant picking")
        .put("unitsMeaning","tool-level PPI defaults 72; no document print-resolution metadata; unit changes affect inputs/steps, never export size")
        .put("limits","delta each axis ±16384; selection ≤4194304 pixels, edge ≤16384, coordinates ±1000000; ≤128 visible pick candidates; image.limits budget; no vector/group/text/colorize pixel flatten")
}
