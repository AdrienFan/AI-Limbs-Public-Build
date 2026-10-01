package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Sweep a thin elliptical nib into a closed, ordinary editable path.
 * Width is layer-local pixels, angle is degrees clockwise from +X; speed uses milliseconds,
 * rather than event count, so the UI and the capability use identical geometry.
 */
internal object ArtCalligraphy {
    const val MAX_SAMPLES = 1000
    private data class V(val x: Double, val y: Double) {
        operator fun plus(b: V) = V(x+b.x,y+b.y)
        operator fun minus(b: V) = V(x-b.x,y-b.y)
        operator fun times(s: Double) = V(x*s,y*s)
        fun dot(b: V) = x*b.x+y*b.y
        fun length() = hypot(x,y)
        fun json() = JSONArray().put(x).put(y)
    }
    private data class Sample(val p: V, val time: Double, val pressure: Double)
    private data class Edge(val center: V, val offset: V, val tangent: V)

    private fun number(p: JSONObject, key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val v=if(p.has(key)) p.getDouble(key) else default
        require(v.isFinite() && v in range) { "$key 超出允许范围" }
        return v
    }
    fun create(p: JSONObject): JSONObject {
        val input=p.getJSONArray("samples")
        require(input.length() in 2..MAX_SAMPLES) { "书法轨迹需要2至1000个采样点" }
        val width=number(p,"width",20.0,0.1..512.0)
        val angle=number(p,"angle",45.0,0.0..180.0)*PI/180.0
        val fixation=number(p,"fixation",1.0,0.0..1.0)
        val thinning=number(p,"thinning",0.0,-1.0..1.0)
        val smoothing=number(p,"smoothing",0.0,0.0..1.0)
        val opacity=number(p,"opacity",1.0,0.0..1.0)
        val pressureEnabled=p.optBoolean("usePressure",true)
        val cap=p.optString("cap","round")
        require(cap in setOf("flat","round")) { "笔端必须为flat或round" }
        val color=p.optString("color","#FF161616")
        require(color.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色必须为#AARRGGBB" }
        val samples=mutableListOf<Sample>()
        var previousTime=-1.0
        var filtered: V?=null
        for(i in 0 until input.length()) {
            val s=input.getJSONObject(i)
            val raw=V(number(s,"x",0.0,-1000000.0..1000000.0),
                number(s,"y",0.0,-1000000.0..1000000.0))
            require(s.has("x") && s.has("y") && s.has("time")) { "每个采样必须包含x/y/time" }
            val time=number(s,"time",0.0,0.0..1.0e12)
            require(time>=previousTime) { "采样时间必须递增或相等，单位毫秒" }
            val pressure=number(s,"pressure",1.0,0.0..1.0)
            val old=filtered
            val point=if(old==null || smoothing==0.0) raw else {
                val dt=max(1.0,time-previousTime)
                old+(raw-old)*(1.0-exp(-dt/(120.0*smoothing)))
            }
            filtered=point;previousTime=time
            // Identical positions do not create a zero-length tangent.
            if(samples.isNotEmpty() && (point-samples.last().p).length()<=0.000001) {
                val last=samples.last()
                samples[samples.lastIndex]=last.copy(pressure=pressure)
            } else samples.add(Sample(point,time,pressure))
        }
        require(samples.size>=2) { "请拖动笔尖绘制非零长度的书法笔画" }
        val edges=mutableListOf<Edge>()
        for(i in samples.indices) {
            val before=samples[max(0,i-1)]
            val after=samples[min(samples.lastIndex,i+1)]
            var delta=after.p-before.p
            if(delta.length()<=0.000001) delta=if(i<samples.lastIndex) after.p-samples[i].p else samples[i].p-before.p
            val tangent=delta*(1.0/delta.length())
            val normal=V(-tangent.y,tangent.x)
            var difference=atan2(normal.y,normal.x)-angle
            while(difference>PI/2) difference-=PI
            while(difference< -PI/2) difference+=PI
            val a=angle+difference*(1.0-fixation)
            val nib=V(cos(a),sin(a));val perpendicular=V(-nib.y,nib.x)
            val speed=delta.length()*1000.0/max(1.0,after.time-before.time)
            val factor=(1.0-thinning*(speed/1000.0).coerceIn(0.0,1.0)).coerceIn(0.05,2.0)
            val w=(width*factor*(if(pressureEnabled) samples[i].pressure else 1.0)).coerceIn(0.1,512.0)
            val major=w/2.0;val minor=max(0.05,w*0.025)
            // Support point of the nib ellipse in the cross-stroke direction.
            // A finite thickness keeps strokes parallel to the nib editable and visible.
            val u=nib.dot(normal);val v=perpendicular.dot(normal)
            val denominator=hypot(major*u,minor*v)
            val offset=(nib*(major*major*u)+perpendicular*(minor*minor*v))*(1.0/denominator)
            edges.add(Edge(samples[i].p,offset,tangent))
        }
        val left=mutableListOf<V>();val right=mutableListOf<V>()
        var old: Edge?=null
        for(edge in edges) {
            // Pinch a reversal at the previous guide point instead of connecting crossed sides.
            if(old!=null && old.offset.dot(edge.offset)<0.0) {
                left.add(old.center);right.add(old.center)
            }
            left.add(edge.center+edge.offset);right.add(edge.center-edge.offset);old=edge
        }
        val points=JSONArray().put(left.first().json());val commands=JSONArray()
        fun line(v: V) { commands.put("L");points.put(v.json()) }
        fun curve(c1: V,c2: V,end: V) {
            commands.put("C");points.put(c1.json()).put(c2.json()).put(end.json())
        }
        fun round(center: V,from: V,to: V,outward: V) {
            val r=from-center
            var extension=V(-r.y,r.x)
            if(extension.dot(outward)<0.0) extension=extension* -1.0
            val middle=center+extension
            val k=0.5522847498307936
            curve(from+extension*k,middle+r*k,middle)
            curve(middle-r*k,to+extension*k,to)
        }
        for(i in 1 until left.size) line(left[i])
        val last=edges.last()
        if(cap=="round") round(last.center,left.last(),right.last(),last.tangent) else line(right.last())
        for(i in right.lastIndex-1 downTo 0) line(right[i])
        val first=edges.first()
        if(cap=="round") round(first.center,right.first(),left.first(),first.tangent* -1.0)
        // Flat start cap uses the native close, so no duplicate anchor is persisted.
        require(commands.length()<=ArtFreehand.MAX_SEGMENTS) { "书法轮廓超过2048段，请将复杂长笔画分段绘制" }
        val shape=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","path")
            .put("points",points).put("commands",commands).put("closed",true)
            .put("fill",color).put("stroke","#00000000").put("strokeWidth",1.0).put("opacity",opacity)
            .put("calligraphy",JSONObject().put("width",width).put("angle",angle*180.0/PI)
                .put("fixation",fixation).put("thinning",thinning).put("smoothing",smoothing)
                .put("usePressure",pressureEnabled).put("cap",cap).put("sampleCount",input.length())
                .put("geometryVersion",1))
        return ArtShapes.normalize(shape)
    }
}
