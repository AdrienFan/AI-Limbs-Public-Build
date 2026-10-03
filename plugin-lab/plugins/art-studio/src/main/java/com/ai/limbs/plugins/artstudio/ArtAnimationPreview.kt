package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject

internal object ArtAnimationPreview {
    /** Onion skins are editor overlays. Exports and ordinary canvas feedback use the original renderer. */
    fun render(store:ArtStore,snapshot:JSONObject,maxEdge:Int?=null):Bitmap {
        val state=snapshot.getJSONObject("state")
        val settings=ArtAnimation.settings(state)
        if(!settings.getBoolean("onion"))return ArtRenderer.render(store,snapshot,maxEdge=maxEdge,colorizeKeys=true)
        val time=settings.getInt("current")
        val animated=ArtAnimation.layers(state).filter {ArtAnimation.keys(it)!=null}
        val neighbors=listOf(
            animated.mapNotNull {layer->ArtAnimation.keys(layer)!!.let {keys->
                (0 until keys.length()).map {keys.getJSONObject(it).getInt("time")}.filter {it<(ArtAnimation.active(layer,time)?.getInt("time") ?: 0)}.maxOrNull()
            }}.maxOrNull() to Color.RED,
            animated.mapNotNull {layer->ArtAnimation.keys(layer)!!.let {keys->
                (0 until keys.length()).map {keys.getJSONObject(it).getInt("time")}.filter {it>time}.minOrNull()
            }}.minOrNull() to Color.BLUE)
        val current=ArtRenderer.render(store,snapshot,maxEdge=maxEdge,colorizeKeys=true)
        try {
            val canvas=Canvas(current)
            for((frame,tint) in neighbors)if(frame!=null) {
                val ghost=ArtAnimation.frame(snapshot,frame)
                val ghostState=ghost.getJSONObject("state").put("background","#00000000")
                val rows=ArtAnimation.layers(ghostState)
                val keep=animated.map {it.getString("id")}.toMutableSet()
                // Retain parent groups so inherited transformations and visibility stay correct.
                for(layer in animated) {
                    var parent=layer.optString("parentId")
                    while(parent.isNotBlank()) {
                        keep.add(parent);parent=rows.first {it.getString("id")==parent}.optString("parentId")
                    }
                }
                rows.filter {it.getString("id") !in keep}.forEach {it.put("visible",false)}
                ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(store,ghostState,current.width,current.height)+
                    ArtBrush.renderOverhead(ghostState,current.width,current.height)+current.width.toLong()*current.height*4,
                    "洋葱皮与当前画布合成")
                val image=ArtRenderer.render(store,ghost,maxEdge=maxEdge)
                try {canvas.drawBitmap(image,0f,0f,Paint().apply {
                    alpha=48;colorFilter=PorterDuffColorFilter(tint,PorterDuff.Mode.SRC_IN)
                })} finally {image.recycle()}
            }
            return current
        } catch(error:Throwable) {current.recycle();throw error}
    }
}
