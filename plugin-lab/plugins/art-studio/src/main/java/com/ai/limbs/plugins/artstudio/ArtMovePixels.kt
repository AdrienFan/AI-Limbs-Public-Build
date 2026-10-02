package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Cut and paste only selected coverage. Original stroke records and unselected pixels remain in place. */
internal object ArtMovePixels {
    data class Capture(val bytes:ByteArray,val bounds:Rect,val mask:JSONObject,val visiblePixels:Int)
    fun capture(store:ArtStore,snapshot:JSONObject,layerId:String,selection:JSONObject):Capture {
        val bounds=ArtMove.bounds(selection);val w=bounds.width();val h=bounds.height()
        val source=ArtMove.window(snapshot,layerId,bounds);val state=source.getJSONObject("state")
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(store,state,w,h)+ArtBrush.renderOverhead(state,w,h)+w.toLong()*h*32,"搬移选区像素")
        val coverage=ByteArray(w*h)
        if(selection.has("coverage")) {
            val sampler=ArtSoftSelection.Sampler(selection)
            for(y in 0 until h)for(x in 0 until w)coverage[y*w+x]=sampler.at(bounds.left+x+0.5,bounds.top+y+0.5).toByte()
        } else {
            val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(mask);canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
                val aa=selection.optJSONObject("creationOptions")?.optDouble("antialias",1.0) ?: 1.0
                canvas.drawPath(ArtSelection.path(selection),Paint().apply {isAntiAlias=aa>0;color=Color.WHITE})
                val row=IntArray(w)
                for(y in 0 until h) {mask.getPixels(row,0,w,0,y,w,1);for(x in 0 until w)coverage[y*w+x]=Color.alpha(row[x]).toByte()}
            } finally {mask.recycle()}
        }
        require(coverage.any {it.toInt()!=0}) {"选区覆盖为空"}
        val canonical=ArtSoftSelection.fromAlpha(coverage,bounds)
        val document=snapshot.getJSONObject("state")
        val pixels=ArtRenderer.render(store,source,logicalSize=document.getInt("width") to document.getInt("height"))
        var visible=0
        val bytes=try {
            val row=IntArray(w)
            for(y in 0 until h) {
                pixels.getPixels(row,0,w,0,y,w,1)
                for(x in 0 until w) {
                    val alpha=maskedAlpha(Color.alpha(row[x]),coverage[y*w+x].toInt() and 255)
                    if(alpha>0)visible++
                    row[x]=(row[x] and 0x00ffffff) or (alpha shl 24)
                }
                pixels.setPixels(row,0,w,0,y,w,1)
            }
            require(visible>0) {"选区内没有可搬移的可见像素"}
            ArtImagePolicy.encodePng(pixels,ArtStore.MAX_ASSET_BYTES)
        } finally {pixels.recycle()}
        return Capture(bytes,bounds,canonical,visible)
    }
    fun maskedAlpha(alpha:Int,coverage:Int):Int {
        require(alpha in 0..255 && coverage in 0..255)
        return (alpha*coverage+127)/255
    }
    fun validate(p:JSONObject) {
        val w=p.getInt("width");val h=p.getInt("height")
        require(w in 1..16384 && h in 1..16384 && w.toLong()*h<=ArtRasterSelection.MAX_PIXELS)
        for(key in listOf("x","y")) {
            val value=p.get(key);require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && abs(value.toDouble())<=1000000)
        }
        for(key in listOf("dx","dy")) {val value=p.get(key);require(value is Number && value.toDouble()%1==0.0 && abs(value.toDouble())<=16384)}
        ArtSelection.validate(p.getJSONObject("sourceSelection"));require(p.getJSONObject("sourceSelection").has("coverage"))
        val a=p.getJSONArray("selectionToLayer");require(a.length()==6)
        for(i in 0 until 6)require(a.getDouble(i).isFinite() && abs(a.getDouble(i))<=1000000)
        val inverse=Matrix();require(ArtShapes.matrix(a).invert(inverse)) {"像素搬移坐标变换不可逆"}
    }
    fun draw(canvas:Canvas,event:JSONObject,store:ArtStore) {
        validate(event)
        val image=ArtImagePolicy.decodeAsset(store.assetFile(event.getString("asset")))
        try {
            require(image.width==event.getInt("width") && image.height==event.getInt("height")) {"搬移像素资源尺寸不匹配"}
            canvas.save()
            try {
                canvas.concat(ArtShapes.matrix(event.getJSONArray("selectionToLayer")))
                val mask=event.getJSONObject("sourceSelection")
                ArtSoftSelection.draw(canvas,mask,erase=true) {
                    // White temporary coverage is merged with DST_OUT; it is never an entire-stroke deletion.
                    canvas.drawRect(event.getInt("x").toFloat(),event.getInt("y").toFloat(),
                        (event.getInt("x")+event.getInt("width")).toFloat(),(event.getInt("y")+event.getInt("height")).toFloat(),Paint().apply {color=Color.WHITE})
                }
                canvas.drawBitmap(image,(event.getInt("x")+event.getInt("dx")).toFloat(),
                    (event.getInt("y")+event.getInt("dy")).toFloat(),Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {canvas.restore()}
        } finally {image.recycle()}
    }
}
