package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Region
import android.util.Base64
import org.json.JSONObject
import java.io.*
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Binary scanline geometry preserves holes and disconnected islands; soft coverage remains separate. */
internal object ArtRasterSelection {
    const val MAX_PIXELS=4194304
    const val MAX_RUNS=32768
    data class Run(val y: Int,val left: Int,val right: Int)
    fun decode(s: JSONObject): List<Run> {
        val w=s.getInt("maskWidth");val h=s.getInt("maskHeight");val count=s.getInt("runCount")
        require(w>0 && h>0 && w.toLong()*h<=MAX_PIXELS && count in 1..MAX_RUNS)
        require(s.getString("encoding")=="deflate-rle-v1")
        val data=s.getString("data");require(data.length<=550000)
        val raw=Base64.decode(data,Base64.NO_WRAP);require(raw.size<=400000)
        val result=ArrayList<Run>(count)
        DataInputStream(InflaterInputStream(ByteArrayInputStream(raw))).use {input->
            repeat(count) {
                val run=Run(input.readInt(),input.readInt(),input.readInt())
                require(run.y in 0 until h && run.left>=0 && run.right<=w && run.right>run.left)
                result.lastOrNull()?.let {previous->
                    require(run.y>previous.y || run.y==previous.y && run.left>previous.right) {"二值选区扫描行顺序无效"}
                }
                result.add(run)
            }
            require(input.read()==-1) {"二值选区数据长度无效"}
        }
        return result
    }
    fun fromMask(mask: ByteArray,w: Int,h: Int,x: Int=0,y: Int=0): JSONObject {
        require(w>0 && h>0 && w.toLong()*h<=MAX_PIXELS && mask.size==w*h)
        var left=w;var top=h;var right=0;var bottom=0
        for(i in mask.indices)if(mask[i].toInt()!=0) {
            left=minOf(left,i%w);right=maxOf(right,i%w+1);top=minOf(top,i/w);bottom=maxOf(bottom,i/w+1)
        }
        if(right<=left || bottom<=top)return ArtBezierSelection.empty()
        val buffer=ByteArrayOutputStream();var count=0
        DataOutputStream(DeflaterOutputStream(buffer)).use {out->
            for(row in top until bottom) {
                var column=left
                while(column<right) {
                    if(mask[row*w+column].toInt()==0) {column++;continue}
                    val start=column
                    while(column<right && mask[row*w+column].toInt()!=0)column++
                    require(++count<=MAX_RUNS) {"选区细碎程度超过32768扫描段，请缩小查找范围"}
                    out.writeInt(row-top);out.writeInt(start-left);out.writeInt(column-left)
                }
            }
        }
        return JSONObject().put("shape","raster").put("x",x+left).put("y",y+top)
            .put("width",right-left).put("height",bottom-top).put("maskWidth",right-left).put("maskHeight",bottom-top)
            .put("runCount",count).put("encoding","deflate-rle-v1")
            .put("data",Base64.encodeToString(buffer.toByteArray(),Base64.NO_WRAP))
    }
    fun path(s: JSONObject): Path {
        val runs=decode(s);val rectangles=Path()
        for(run in runs)rectangles.addRect(run.left.toFloat(),run.y.toFloat(),run.right.toFloat(),run.y+1f,Path.Direction.CW)
        val region=Region().apply {setPath(rectangles,Region(0,0,s.getInt("maskWidth"),s.getInt("maskHeight")))}
        val outline=region.boundaryPath
        outline.transform(Matrix().apply {
            setScale((s.getDouble("width")/s.getInt("maskWidth")).toFloat(),(s.getDouble("height")/s.getInt("maskHeight")).toFloat())
            postTranslate(s.getDouble("x").toFloat(),s.getDouble("y").toFloat())
        })
        return outline
    }
}
