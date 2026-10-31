package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.abs

/** Hidden color never enters visible compositing; the asset stores masked straight RGB with its own alpha. */
internal object ArtHiddenColors {
    fun validatePixels(p:FloatArray,size:Int) {
        require(p.size==size)
        for(i in p.indices) {
            if(i%16384==0)ArtFilterRaster.cancelled()
            require(p[i].isFinite() && if(i%4==3)p[i] in 0f..1f else abs(p[i])<=65504)
        }
    }
    fun hasPixels(p:FloatArray)=p.indices.step(4).any {p[it+3]>0f}
    fun validate(state:JSONObject) {
        fun check(layer:JSONObject) {
            if(!layer.has("hiddenColors"))return
            val o=layer.getJSONObject("hiddenColors")
            require(o.keys().asSequence().all {it in setOf("format","asset","width","height")})
            require(o.getInt("format")==1 && o.getString("asset").matches(Regex("[a-f0-9-]{36}")))
            require(o.getInt("width")==state.getInt("width") && o.getInt("height")==state.getInt("height")) {"隐藏颜色尺寸已过期"}
        }
        for(layer in ArtMenuOperations.layers(state)) {
            check(layer)
            layer.optJSONArray("animationKeys")?.let {keys->for(i in 0 until keys.length())check(keys.getJSONObject(i).getJSONObject("content"))}
        }
    }
    /** Invalidate auxiliary pixels when ordinary pixel/geometry edits change their coordinate basis. */
    fun basis(layer:JSONObject):String=JSONObject().apply {
        for(key in listOf("kind","asset","strokes","contentOrder","pixelEdits","x","y","rotation","scale","affine"))
            if(layer.has(key))put(key,layer.get(key))
    }.toString()
}
