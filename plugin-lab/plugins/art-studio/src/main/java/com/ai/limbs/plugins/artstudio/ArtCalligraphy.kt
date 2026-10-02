package com.ai.limbs.plugins.artstudio

import android.graphics.PathMeasure
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
    private data class Sample(val p: V, val time: Double, val pressure: Double,val nibAngle:Double)
    private data class Edge(val center: V, val offset: V, val tangent: V)

    private fun number(p: JSONObject, key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val v=if(p.has(key)) p.getDouble(key) else default
        require(v.isFinite() && v in range) { "$key 超出允许范围" }
        return v
    }
    val settingKeys=setOf("width","angle","fixation","thinning","smoothing","opacity","usePressure","cap","color","mass","drag","useTilt","followPath","followReverse")
    fun settings(p:JSONObject):JSONObject {
        val out=JSONObject()
        for((key,default,range) in listOf(Triple("width",20.0,0.1..512.0),Triple("angle",45.0,0.0..180.0),
            Triple("fixation",1.0,0.0..1.0),Triple("thinning",0.0,-1.0..1.0),Triple("smoothing",0.0,0.0..1.0),
            Triple("opacity",1.0,0.0..1.0),Triple("mass",0.0,0.0..20.0),Triple("drag",1.0,0.0..1.0)))out.put(key,number(p,key,default,range))
        for((key,default) in listOf("usePressure" to true,"useTilt" to false,"followPath" to false,"followReverse" to false))
            out.put(key,if(p.has(key))p.getBoolean(key) else default)
        val cap=if(p.has("cap"))p.getString("cap") else "round";require(cap in setOf("flat","round"));out.put("cap",cap)
        val color=if(p.has("color"))p.getString("color") else "#FF161616";require(color.matches(Regex("#[A-Fa-f0-9]{8}")));out.put("color",color)
        return out
    }
    fun guide(state:JSONObject,layerId:String,p:JSONObject):JSONObject? {
        if(!settings(p).getBoolean("followPath"))return null
        val layer=ArtShapes.layer(state,layerId)
        val shape=ArtShapes.items(layer).firstOrNull {it.getString("id")==p.getString("followPathId")} ?: error("跟随路径不存在，请重新选择")
        require(ArtShapes.visible(state,layer)&&shape.getString("kind")=="path"&&shape.getBoolean("visible")&&shape.getDouble("opacity")>0) {"只能跟随当前矢量层的可见路径"}
        val parts=ArtPathTopology.parts(shape);val index=if(p.has("followSubpath"))p.getInt("followSubpath") else 0
        require(index in parts.indices) {"跟随子路径编号超出范围"}
        val result=ArtPathTopology.write(shape,listOf(parts[index]));measure(result);return result
    }
    private fun measure(guide:JSONObject):PathMeasure {
        val path=ArtShapes.path(guide);path.transform(ArtShapes.matrix(guide.getJSONArray("matrix")))
        return PathMeasure(path,false).also {require(it.length.isFinite()&&it.length>0.000001f) {"跟随路径长度为零"}}
    }
    fun start(guide:JSONObject,reverse:Boolean):FloatArray {
        val measure=measure(guide);val xy=FloatArray(2);check(measure.getPosTan(if(reverse)measure.length else 0f,xy,null));return xy
    }
    /** Inclination 0 is upright: retain the current nib direction until the pen leans again. */
    fun nibAngle(p:JSONObject,sample:JSONObject,previous:Double):Double {
        if(!p.getBoolean("useTilt"))return previous
        require(sample.has("tilt")&&sample.has("orientation")) {"倾斜模式每点须提供tilt和orientation"}
        val tilt=number(sample,"tilt",0.0,0.0..90.0);val orientation=number(sample,"orientation",0.0,0.0..360.0)
        return if(tilt==0.0)previous else ((orientation+90.0)%180.0)*PI/180.0
    }
    /** Resample the traversed guide at <=4 local pixels so sparse pointer input still follows curves. */
    private fun followSamples(input:JSONArray,options:JSONObject,measure:PathMeasure):JSONArray {
        val out=JSONArray();var previous:V?=null;var previousTime=-1.0;var previousPressure=1.0
        var previousAngle=options.getDouble("angle")*PI/180.0;var position=0.0
        val reverse=options.getBoolean("followReverse");val useTilt=options.getBoolean("useTilt")
        fun emit(distance:Double,time:Double,pressure:Double,angle:Double) {
            require(out.length()<MAX_SAMPLES) {"跟随轨迹超过1000个中心点，请分段书写或缩短路径"}
            val xy=FloatArray(2);check(measure.getPosTan((if(reverse)measure.length-distance else distance).toFloat(),xy,null))
            val sample=JSONObject().put("x",xy[0].toDouble()).put("y",xy[1].toDouble()).put("time",time).put("pressure",pressure)
            if(useTilt)sample.put("resolvedNibAngle",angle)
            out.put(sample)
        }
        for(i in 0 until input.length()) {
            val sample=input.getJSONObject(i);require(sample.has("x")&&sample.has("y")&&sample.has("time"))
            val raw=V(number(sample,"x",0.0,-1000000.0..1000000.0),number(sample,"y",0.0,-1000000.0..1000000.0))
            val time=number(sample,"time",0.0,0.0..1.0e12);require(time>=previousTime) {"采样时间必须递增或相等"}
            val pressure=number(sample,"pressure",1.0,0.0..1.0);val angle=nibAngle(options,sample,previousAngle)
            val old=previous
            if(old==null)emit(0.0,time,pressure,angle) else if(position<measure.length) {
                val travel=(raw-old).length();val end=minOf(measure.length.toDouble(),position+travel)
                val count=ceil((end-position)/4.0).toInt()
                require(count<=MAX_SAMPLES-out.length()) {"跟随轨迹超过1000个中心点，请分段书写或缩短路径"}
                var difference=angle-previousAngle
                while(difference>PI/2)difference-=PI
                while(difference< -PI/2)difference+=PI
                for(j in 1..count) {
                    val distance=position+(end-position)*j/count
                    val t=(distance-position)/travel
                    emit(distance,previousTime+(time-previousTime)*t,previousPressure+(pressure-previousPressure)*t,previousAngle+difference*t)
                }
                position=end
            }
            previous=raw;previousTime=time;previousPressure=pressure;previousAngle=angle
        }
        return out
    }
    fun create(p: JSONObject,guide:JSONObject?=null): JSONObject {
        val options=settings(p)
        require(options.getBoolean("followPath")== (guide!=null)) {"跟随模式必须解析当前工程路径"}
        val measure=guide?.let {measure(it)}
        val reverse=options.getBoolean("followReverse");val mass=options.getDouble("mass");val drag=options.getDouble("drag")
        var velocity=V(0.0,0.0);var simulated:V?=null
        var currentAngle=options.getDouble("angle")*PI/180.0
        val input=p.getJSONArray("samples")
        require(input.length() in 2..MAX_SAMPLES) { "书法轨迹需要2至1000个采样点" }
        val working=if(measure!=null)followSamples(input,options,measure) else input
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
        for(i in 0 until working.length()) {
            val s=working.getJSONObject(i)
            val raw=V(number(s,"x",0.0,-1000000.0..1000000.0),
                number(s,"y",0.0,-1000000.0..1000000.0))
            require(s.has("x") && s.has("y") && s.has("time")) { "每个采样必须包含x/y/time" }
            val time=number(s,"time",0.0,0.0..1.0e12)
            require(time>=previousTime) { "采样时间必须递增或相等，单位毫秒" }
            val pressure=number(s,"pressure",1.0,0.0..1.0)
            currentAngle=if(measure!=null&&options.getBoolean("useTilt"))s.getDouble("resolvedNibAngle") else nibAngle(options,s,currentAngle)
            val base=if(measure!=null)raw else {
                val old=simulated
                val next=if(old==null||mass==0.0&&drag==1.0)raw else {
                    // Match Krita's sample-step force/velocity responsibilities; replay stores final geometry.
                    velocity=velocity*(1.0-drag)+(raw-old)*(1.0/(mass*mass+1.0));old+velocity
                }
                simulated=next;next
            }
            val old=filtered
            val point=if(measure!=null||old==null || smoothing==0.0) base else {
                val dt=max(1.0,time-previousTime)
                old+(base-old)*(1.0-exp(-dt/(120.0*smoothing)))
            }
            require(point.x.isFinite()&&point.y.isFinite()&&abs(point.x)<=1000000&&abs(point.y)<=1000000) {"书法中心轨迹超出可编辑范围"}
            filtered=point;previousTime=time
            // Identical positions do not create a zero-length tangent.
            if(samples.isNotEmpty() && (point-samples.last().p).length()<=0.000001) {
                val last=samples.last()
                samples[samples.lastIndex]=last.copy(pressure=pressure,nibAngle=currentAngle)
            } else samples.add(Sample(point,time,pressure,currentAngle))
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
            val sampleAngle=samples[i].nibAngle
            var difference=atan2(normal.y,normal.x)-sampleAngle
            while(difference>PI/2) difference-=PI
            while(difference< -PI/2) difference+=PI
            val a=sampleAngle+difference*(1.0-fixation)
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
                .put("mass",mass).put("drag",drag).put("useTilt",options.getBoolean("useTilt"))
                .put("followPath",measure!=null).put("followReverse",reverse).put("centerSampleCount",samples.size).put("geometryVersion",2))
        if(measure!=null)shape.getJSONObject("calligraphy").put("followPathId",p.getString("followPathId")).put("followSubpath",if(p.has("followSubpath"))p.getInt("followSubpath") else 0)
        return ArtShapes.normalize(shape)
    }
    fun info()=JSONObject().put("defaults",settings(JSONObject())).put("samples","2–1000 {x,y,time,pressure?,tilt?,orientation?}; x/y layer-local pixels, time milliseconds; useTilt requires both tilt 0–90 degrees from upright and orientation 0–360 clockwise from layer +X. Upright retains last nib direction (initial custom angle).")
        .put("follow","followPath=true requires same-layer visible path followPathId; followSubpath defaults 0 (path.nodes.subpaths), followReverse defaults false. Start at selected contour start/end; cumulative pointer travel advances arc length and stops at its end, resampled at <=4 local pixels (<=1000 center samples), one traversal for closed contours. Original guide remains unchanged. Mass/Drag/smoothing only affect free mode.")
        .put("inertia","Mass 0–20, Drag 0–1; per input sample velocity=velocity*(1-Drag)+(cursor-position)/(Mass²+1), position+=velocity. Default Mass=0/Drag=1 preserves direct input. No forced jump to cursor on release. Smoothing follows inertia, speed thinning still uses milliseconds.")
        .put("profiles","calligraphy.profiles/profile.get/profile.save/profile.delete persist up to128 unique UUID profiles. settings includes brush size/color/opacity and all behavioral controls, excluding document/path ID and subpath references. shape.calligraphy accepts profileId with explicit parameters overriding saved settings; final geometry is recorded, so history does not depend on profiles or guide remaining available.")
}
