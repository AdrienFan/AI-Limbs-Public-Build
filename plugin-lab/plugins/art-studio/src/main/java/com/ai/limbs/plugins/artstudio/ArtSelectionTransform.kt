package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

internal object ArtSelectionTransform {
    fun snapped(origin:ArtPathGeometry.Vec,point:ArtPathGeometry.Vec,step:Double):ArtPathGeometry.Vec {
        require(step.isFinite() && step in 0.0..180.0)
        if(step==0.0)return point
        val delta=point-origin;val angle=atan2(delta.y,delta.x)
        val radians=Math.toRadians(round(Math.toDegrees(angle)/step)*step)
        return origin+ArtPathGeometry.Vec(cos(radians),sin(radians))*delta.length()
    }
    private fun curve(s:JSONObject,m:Matrix):JSONObject {
        val nodes=ArtBezierSelection.nodes(s)
        fun map(p:ArtPathGeometry.Vec):ArtPathGeometry.Vec {
            val xy=floatArrayOf(p.x.toFloat(),p.y.toFloat());m.mapPoints(xy)
            return ArtPathGeometry.Vec(xy[0].toDouble(),xy[1].toDouble())
        }
        nodes.forEach {it.point=map(it.point);it.incoming=it.incoming?.let(::map);it.outgoing=it.outgoing?.let(::map)}
        return ArtBezierSelection.fromNodes(ArtPathGeometry.json(nodes))
    }
    private fun mask(s:JSONObject,m:Matrix,w:Int,h:Int,clip:Boolean):JSONObject {
        val path=ArtSelection.path(s).apply {transform(m)};val box=RectF();path.computeBounds(box,true)
        val r=Rect(floor(box.left.toDouble()).toInt(),floor(box.top.toDouble()).toInt(),ceil(box.right.toDouble()).toInt(),ceil(box.bottom.toDouble()).toInt())
        if(clip && !r.intersect(0,0,w,h))return ArtBezierSelection.empty()
        if(r.isEmpty)return ArtBezierSelection.empty()
        require(r.width().toLong()*r.height()<=ArtRasterSelection.MAX_PIXELS) {"旋转后的选区超出处理预算"}
        ArtImagePolicy.requireBytes(r.width().toLong()*r.height()*16,"选区旋转")
        val inverse=Matrix();require(m.invert(inverse))
        val sampler=ArtSoftSelection.Sampler(s);val alpha=ByteArray(r.width()*r.height());val row=FloatArray(r.width()*2)
        for(y in 0 until r.height()) {
            for(x in 0 until r.width()) {row[x*2]=r.left+x+0.5f;row[x*2+1]=r.top+y+0.5f}
            inverse.mapPoints(row)
            for(x in 0 until r.width())alpha[y*r.width()+x]=sampler.at(row[x*2].toDouble(),row[x*2+1].toDouble()).toByte()
        }
        return ArtSoftSelection.fromAlpha(alpha,r)
    }
    fun rotate(selection:JSONObject,p:JSONObject,w:Int,h:Int):JSONObject {
        ArtSelection.validate(selection)
        val degrees=p.getDouble("degrees");require(degrees.isFinite() && abs(degrees)<=36000)
        val px=p.optDouble("pivotX",selection.getDouble("x")+selection.getDouble("width")/2)
        val py=p.optDouble("pivotY",selection.getDouble("y")+selection.getDouble("height")/2)
        require(px.isFinite() && py.isFinite() && abs(px)<=1000000 && abs(py)<=1000000)
        if(selection.getDouble("width")==0.0 || selection.getDouble("height")==0.0)return JSONObject(selection.toString())
        return transformed(selection,Matrix().apply {setRotate(degrees.toFloat(),px.toFloat(),py.toFloat())},w,h)
    }
    fun transformed(selection:JSONObject,m:Matrix,w:Int,h:Int,clip:Boolean=true):JSONObject {
        ArtSelection.validate(selection)
        if(selection.getDouble("width")==0.0 || selection.getDouble("height")==0.0)return ArtBezierSelection.empty()
        if(selection.has("curveParts") || selection.optString("shape")=="compound") {
            val parts=ArtCurveSoftSelection.parts(selection)
            parts.forEach {part->val child=part.getJSONObject("selection")
                part.put("selection",if(child.optString("shape")=="bezier")curve(child,m) else mask(child,m,w,h,clip))}
            return ArtCurveSoftSelection.rebuild(parts,w,h)
        }
        return if(selection.optString("shape")=="bezier")curve(selection,m) else mask(selection,m,w,h,clip)
    }
}
