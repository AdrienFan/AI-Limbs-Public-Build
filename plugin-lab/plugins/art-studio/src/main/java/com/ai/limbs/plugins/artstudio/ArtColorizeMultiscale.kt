package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Global coarse labels guide overlapping full resolution tiles; the saved output keeps every pixel. */
internal object ArtColorizeMultiscale {
    const val TILE_PIXELS=1048576
    private const val COARSE_PIXELS=262144
    fun estimate(mask:JSONObject,pixels:Long):Long = if(pixels<=TILE_PIXELS)
        ArtColorizeSolver.workingBytes(mask,pixels) else pixels*8+
        ArtColorizeSolver.workingBytes(mask,TILE_PIXELS.toLong())+COARSE_PIXELS*16L

    fun solve(state:JSONObject,source:Bitmap,mask:JSONObject,area:Rect):ArtColorizeSolver.Result {
        val count=area.width().toLong()*area.height()
        if(count<=TILE_PIXELS)return ArtColorizeSolver.solve(state,source,mask,area)
        require(count<=ArtColorize.MAX_OUTPUT_PIXELS)
        ArtImagePolicy.requireBytes(estimate(mask,count),"多尺度上色")
        var scale=2
        while(ceil(area.width().toDouble()/scale)*ceil(area.height().toDouble()/scale)>COARSE_PIXELS)scale++
        val cw=(area.width()+scale-1)/scale;val ch=(area.height()+scale-1)/scale
        val coarseBitmap=ArtFloatPixels.bitmap(cw,ch)
        val coarsePixels=FloatArray(cw*ch*4);val peaks=IntArray(cw)
        // Max darkness pooling preserves thin line barriers that bilinear shrinking can erase.
        for(cy in 0 until ch) {
            peaks.fill(-1)
            for(y in area.top+cy*scale until minOf(area.bottom,area.top+(cy+1)*scale)) {
                val row=ArtFloatPixels.read(source,Rect(area.left,y,area.right,y+1))
                for(x in 0 until area.width()) {
                    val dark=ArtFloatPixels.darkness(row,x*4)
                    val cx=x/scale;if(dark>peaks[cx]) {peaks[cx]=dark;for(c in 0..3)coarsePixels[(cy*cw+cx)*4+c]=row[x*4+c]}
                }
            }
        }
        ArtFloatPixels.write(coarseBitmap,coarsePixels)
        val documentToCoarse=Matrix().apply {setValues(floatArrayOf(1f/scale,0f,-area.left.toFloat()/scale,
            0f,1f/scale,-area.top.toFloat()/scale,0f,0f,1f))}
        val coarseState=JSONObject().put("width",cw).put("height",ch)
        state.optJSONObject("selection")?.let {coarseState.put("selection",ArtSelectionTransform.transformed(it,documentToCoarse,cw,ch))}
        val coarseMask=JSONObject(mask.toString())
        val toDocument=if(mask.has("solverToDocument"))ArtShapes.matrix(mask.getJSONArray("solverToDocument")) else Matrix()
        val toCoarse=Matrix().apply {setConcat(documentToCoarse,toDocument)}
        coarseMask.put("solverToDocument",ArtShapes.encode(toCoarse)).put("solverMinimumWidth",2)
        val options=coarseMask.getJSONObject("colorize").getJSONObject("settings")
        for(key in listOf("edgeDetectionSize","fuzzyRadius"))options.put(key,options.getDouble(key)/scale)
        options.put("gapClose",ceil(options.getDouble("gapClose")/scale).toInt())
        val coarse=try {ArtColorizeSolver.solve(coarseState,coarseBitmap,coarseMask,Rect(0,0,cw,ch))}
            finally {coarseBitmap.recycle()}
        coarse.bitmap.recycle()
        val settings=mask.getJSONObject("colorize").getJSONObject("settings")
        val halo=ceil(3*(settings.getDouble("fuzzyRadius")+
            if(settings.getBoolean("useEdgeDetection"))1.5*settings.getDouble("edgeDetectionSize") else 0.0)).toInt()+settings.getInt("gapClose")*2+8
        val side=sqrt(TILE_PIXELS.toDouble()).toInt()
        require(halo*2<side) {"大画幅的滤波半径超出分块重叠预算，请减小模糊／边缘半径"}
        val step=minOf(512,side-2*halo)
        val output=ArtFloatPixels.bitmap(area.width(),area.height())
        var filled=0;var seeds=0;var cleaned=0;var cleanedSeeds=0;var regions=0
        try {
            for(top in area.top until area.bottom step step)for(left in area.left until area.right step step) {
                check(!Thread.currentThread().isInterrupted) {"上色蒙版求解已取消"}
                val core=Rect(left,top,minOf(left+step,area.right),minOf(top+step,area.bottom))
                val tile=Rect(maxOf(area.left,core.left-halo),maxOf(area.top,core.top-halo),
                    minOf(area.right,core.right+halo),minOf(area.bottom,core.bottom+halo))
                val tw=tile.width();val th=tile.height();val prior=IntArray(tw*th) {-1}
                // Only the outer tile boundary carries global guidance. Local keys are hard seeds.
                for(y in 0 until th)for(x in 0 until tw)if(x==0 || y==0 || x==tw-1 || y==th-1) {
                    val cx=((tile.left+x-area.left)/scale).coerceIn(0,cw-1)
                    val cy=((tile.top+y-area.top)/scale).coerceIn(0,ch-1)
                    prior[y*tw+x]=coarse.labels[cy*cw+cx]
                }
                val result=ArtColorizeSolver.solve(state,source,mask,tile,prior)
                try {
                    val src=Rect(core.left-tile.left,core.top-tile.top,core.right-tile.left,core.bottom-tile.top)
                    val dst=Rect(core.left-area.left,core.top-area.top,core.right-area.left,core.bottom-area.top)
                    Canvas(output).drawBitmap(result.bitmap,src,dst,null)
                    val pixels=IntArray(core.width()*core.height())
                    result.bitmap.getPixels(pixels,0,core.width(),src.left,src.top,core.width(),core.height())
                    filled+=pixels.count {Color.alpha(it)>0}
                    // Statistics include overlap work and are reported separately from final filled pixels.
                    seeds+=result.seeds;cleaned+=result.cleanup.pixels;cleanedSeeds+=result.cleanup.seedPixels;regions+=result.cleanup.regions
                } finally {result.bitmap.recycle()}
            }
            return ArtColorizeSolver.Result(output,area.left,area.top,filled,seeds,
                ArtColorizeFilters.Cleanup(cleaned,cleanedSeeds,regions),IntArray(0),scale)
        } catch(error:Throwable) {output.recycle();throw error}
    }
}
