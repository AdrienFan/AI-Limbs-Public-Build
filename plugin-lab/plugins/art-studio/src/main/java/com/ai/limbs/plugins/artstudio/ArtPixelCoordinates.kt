package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Matrix
import org.json.JSONObject

/** Pixel algorithms sample document space; the recorded inverse maps their output to its
 * owning layer. Keeping that inverse in history also preserves edits after later transforms.
 */
internal object ArtPixelCoordinates {
    fun target(state:JSONObject,id:String):JSONObject {
        val layer=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==id} ?: error("像素目标图层不存在")
        require(layer.getString("kind") in setOf("paint","image")) {"请选择绘画或图像图层"}
        require(ArtMove.visibility(state,layer)>0 && !ArtMenuOperations.isLocked(state,layer)) {"目标图层或父组已隐藏、透明或锁定"}
        inverse(state,layer)
        return layer
    }
    fun inverse(state:JSONObject,layer:JSONObject)=Matrix().also {
        require(ArtShapes.layerMatrix(state,layer).invert(it)) {"目标图层变换不可逆"}
    }
    fun stamp(event:JSONObject,state:JSONObject,layer:JSONObject):JSONObject =
        event.put("documentToLayer",ArtShapes.encode(inverse(state,layer)))
    fun validate(event:JSONObject) {
        val inverse=ArtShapes.matrix(event.getJSONArray("documentToLayer"))
        require(inverse.invert(Matrix())) {"像素写回变换不可逆"}
    }
    fun copy(event:JSONObject,source:JSONObject):JSONObject {
        if(source.has("documentToLayer")) {validate(source);event.put("documentToLayer",source.getJSONArray("documentToLayer"))}
        return event
    }
    fun draw(canvas:Canvas,event:JSONObject,block:()->Unit) {
        canvas.save()
        try {
            if(event.has("documentToLayer")) {validate(event);canvas.concat(ArtShapes.matrix(event.getJSONArray("documentToLayer")))}
            block()
        } finally {canvas.restore()}
    }
}
