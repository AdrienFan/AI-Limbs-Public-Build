package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*

/** Measurement is view/resource state; it never changes document revision or history. */
internal object ArtMeasure {
    fun defaults()=JSONObject().put("revision",0).put("unit","px").put("ppi",72.0).put("baseline",0.0).put("angleStep",0.0).put("dragMode","auto")
    fun settings(old:JSONObject,p:JSONObject):JSONObject {
        require(old.getLong("revision")>=0)
        val next=JSONObject(old.toString())
        for(k in listOf("unit","ppi","baseline","angleStep","dragMode"))if(p.has(k))next.put(k,p.get(k))
        require(next.getString("unit") in setOf("px","mm","cm","in","pt"))
        require(next.getDouble("ppi").isFinite()&&next.getDouble("ppi") in 1.0..2400.0)
        require(next.getDouble("baseline").isFinite()&&abs(next.getDouble("baseline"))<=36000)
        require(next.getDouble("angleStep").isFinite()&&next.getDouble("angleStep") in 0.0..180.0)
        require(next.getString("dragMode") in setOf("auto","new","translate","baseline"));return next
    }
    fun normalize(a:Double)=((a+180)%360+360)%360-180
    fun evaluate(p:JSONObject,options:JSONObject):JSONObject {
        val o=settings(options,p)
        var x0=p.getDouble("x0");var y0=p.getDouble("y0");var x1=p.getDouble("x1");var y1=p.getDouble("y1")
        require(listOf(x0,y0,x1,y1).all {it.isFinite()&&abs(it)<=1000000})
        val length=hypot(x1-x0,y1-y0);val base=o.getDouble("baseline");val step=o.getDouble("angleStep")
        if(step>0&&length>0) {
            val a=base+round(normalize(Math.toDegrees(atan2(y1-y0,x1-x0))-base)/step)*step
            x1=x0+length*cos(Math.toRadians(a));y1=y0+length*sin(Math.toRadians(a))
        }
        val dx=p.optDouble("dx",0.0);val dy=p.optDouble("dy",0.0)
        require(dx.isFinite()&&dy.isFinite()&&abs(dx)<=1000000&&abs(dy)<=1000000)
        x0+=dx;y0+=dy;x1+=dx;y1+=dy
        require(listOf(x0,y0,x1,y1).all {abs(it)<=1000000})
        val degrees=if(length>0)Math.toDegrees(atan2(y1-y0,x1-x0)) else 0.0
        val relative=if(length>0)normalize(degrees-base) else 0.0
        return JSONObject().put("x0",x0).put("y0",y0).put("x1",x1).put("y1",y1)
            .put("distancePx",length).put("distance",ArtMove.fromPixels(length,o.getString("unit"),o.getDouble("ppi")))
            .put("unit",o.getString("unit")).put("ppi",o.getDouble("ppi")).put("degrees",degrees)
            .put("relativeDegrees",relative).put("acuteDegrees",min(abs(relative),180-abs(relative)))
            .put("baseline",base).put("resolutionSource","toolPpi").put("degenerate",length==0.0)
    }
    fun info()=JSONObject().put("units",org.json.JSONArray(listOf("px","mm","cm","in","pt")))
        .put("physicalResolution","tool PPI, default 72; document has no print-resolution metadata")
        .put("angle","clockwise from +X in document coordinates; relativeDegrees signed [-180,180), acuteDegrees against unoriented baseline")
        .put("phone","Drag endpoints or line body; Shift=angleStep or 15°, Alt=whole line, Ctrl=set baseline. Phone modes expose the same gestures without a keyboard; no history writes")
}
