package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal data class AssistantPoint(val x: Double, val y: Double) {
    operator fun plus(p: AssistantPoint) = AssistantPoint(x+p.x,y+p.y)
    operator fun minus(p: AssistantPoint) = AssistantPoint(x-p.x,y-p.y)
    operator fun times(k: Double) = AssistantPoint(x*k,y*k)
    fun dot(p: AssistantPoint) = x*p.x+y*p.y
    fun length() = hypot(x,y)
    fun json() = JSONArray().put(x).put(y)
}

/** Document-space guides, deliberately excluded from artwork layers and export rendering. */
internal object ArtAssistants {
    const val MAX = 32
    val types = linkedMapOf("ruler" to "直尺", "infinite_ruler" to "无限直尺",
        "parallel_ruler" to "平行尺", "ellipse" to "椭圆", "concentric_ellipse" to "同心椭圆",
        "vanishing_point" to "消失点")
    val pending = listOf("三次曲线尺规", "透视网格", "透视椭圆", "双点透视组合", "鱼眼", "曲线透视", "局部作用区域", "固定长度单位")
    val brushTools = setOf("ink","pencil","soft","spray","eraser","calligraphy","mirror","dyna","line")
    fun count(type: String) = when(type) {
        "vanishing_point" -> 1
        "ellipse","concentric_ellipse" -> 3
        else -> { require(type in types); 2 }
    }
    fun items(state: JSONObject): List<JSONObject> {
        val a = state.optJSONArray("assistants") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun selected(state: JSONObject) = state.optString("selectedAssistantId","")
    fun settings(state: JSONObject): JSONObject {
        val p = state.optJSONObject("assistantSettings") ?: JSONObject()
        return JSONObject().put("visible",p.optBoolean("visible",true))
            .put("snapping",p.optBoolean("snapping",false))
            .put("onlySelected",p.optBoolean("onlySelected",true))
            .put("thresholdDp",p.optDouble("thresholdDp",16.0))
    }
    fun points(a: JSONObject): List<AssistantPoint> {
        val v=a.getJSONArray("points")
        return (0 until v.length()).map { val p=v.getJSONArray(it); AssistantPoint(p.getDouble(0),p.getDouble(1)) }
    }
    fun normalize(source: JSONObject): JSONObject {
        val a=JSONObject(source.toString())
        require(a.getString("id").matches(Regex("[a-f0-9-]{36}"))) { "尺规标识无效" }
        val type=a.getString("type");require(type in types) { "未实现的尺规类型：$type" }
        val raw=a.getJSONArray("points");require(raw.length()==count(type)) { "控制点数量不正确" }
        for(i in 0 until raw.length()) {
            val p=raw.getJSONArray(i)
            require(p.length()==2 && (0..1).all { p.getDouble(it).isFinite() && abs(p.getDouble(it))<=1_000_000.0 }) { "控制点坐标无效" }
        }
        val p=points(a)
        if(p.size>=2) require((p[1]-p[0]).length()>=0.01) { "尺规两端不能重合" }
        if(p.size==3) require(geometryValid(a)) { "第三点须位于主轴两端之间的侧方，不能在主轴上，椭圆须在可编辑范围内" }
        for(key in listOf("visible","enabled","locked")) if(a.has(key)) require(a.get(key) is Boolean) { "尺规开关必须为布尔值" }
        if(a.has("name"))require(a.get("name") is String)
        for(key in listOf("subdivisions","rays")) if(a.has(key)) require(a.get(key) is Number && a.getDouble(key)==a.getInt(key).toDouble())
        a.put("name",a.optString("name",types.getValue(type)).take(100))
        a.put("visible",a.optBoolean("visible",true)).put("enabled",a.optBoolean("enabled",true))
            .put("locked",a.optBoolean("locked",false))
        val sub=a.optInt("subdivisions",10); require(sub in 0..100)
        val rays=a.optInt("rays",16); require(rays in 4..64)
        return a.put("subdivisions",sub).put("rays",rays)
    }
    fun validate(state: JSONObject) {
        val all=items(state);require(all.size<=MAX)
        require(all.map { it.getString("id") }.distinct().size==all.size)
        all.forEach { normalize(it) }
        require(selected(state).isEmpty() || all.any { it.getString("id")==selected(state) })
        state.optJSONObject("assistantSettings")?.let { p ->
            for(key in listOf("visible","snapping","onlySelected")) if(p.has(key))require(p.get(key) is Boolean)
            if(p.has("thresholdDp"))require(p.get("thresholdDp") is Number)
        }
        val t=settings(state).getDouble("thresholdDp");require(t.isFinite() && t in 4.0..64.0)
    }
    fun edit(state: JSONObject,type: String,p: JSONObject) {
        val all=items(state).toMutableList()
        when(type) {
            "ASSISTANT_CREATE" -> {
                require(all.size<MAX) { "一个工程最多32个辅助尺规" }
                val a=normalize(p.getJSONObject("assistant"));require(all.none { it.getString("id")==a.getString("id") })
                all.add(a);state.put("selectedAssistantId",a.getString("id"))
            }
            "ASSISTANT_SETTINGS" -> {
                val update=p.getJSONObject("settings");val keys=update.keys().asSequence().toList()
                require(keys.isNotEmpty() && keys.all { it in setOf("visible","snapping","onlySelected","thresholdDp") })
                val next=settings(state);keys.forEach { next.put(it,update.get(it)) };state.put("assistantSettings",next)
            }
            "ASSISTANT_SELECT" -> {
                val id=p.getString("id");require(id.isEmpty() || all.any { it.getString("id")==id })
                state.put("selectedAssistantId",id)
            }
            "ASSISTANT_UPDATE","ASSISTANT_DELETE" -> {
                val a=all.firstOrNull { it.getString("id")==p.getString("id") } ?: error("尺规已删除，请刷新")
                if(type=="ASSISTANT_DELETE") {
                    require(!a.getBoolean("locked")) { "尺规已锁定" };all.remove(a)
                    if(selected(state)==p.getString("id")) state.put("selectedAssistantId","")
                } else {
                    val update=p.getJSONObject("changes");val keys=update.keys().asSequence().toList()
                    require(keys.isNotEmpty() && keys.all { it in setOf("points","name","visible","enabled","locked","subdivisions","rays") })
                    require(!a.getBoolean("locked") || keys==listOf("locked")) { "请先解锁尺规" }
                    keys.forEach { a.put(it,update.get(it)) }
                    state.put("selectedAssistantId",a.getString("id"))
                }
            }
            else -> error("未知尺规操作")
        }
        state.put("assistants",JSONArray(all.map(::normalize)));validate(state)
    }
    data class Ellipse(val center: AssistantPoint,val axis: AssistantPoint,val normal: AssistantPoint,val a: Double,val b: Double)
    fun ellipse(a: JSONObject): Ellipse {
        val p=points(a);val c=(p[0]+p[1])*0.5;val axis=(p[1]-p[0])*(1.0/(p[1]-p[0]).length())
        val normal=AssistantPoint(-axis.y,axis.x)
        val major=(p[1]-p[0]).length()*0.5
        val d=p[2]-c;val divisor=1.0-d.dot(axis).pow(2)/major.pow(2)
        require(divisor>1e-12) { "椭圆第三点超出主轴两端" }
        return Ellipse(c,axis,normal,major,abs(d.dot(normal))/sqrt(divisor))
    }
    fun line(point: AssistantPoint,a: AssistantPoint,b: AssistantPoint,finite: Boolean=false): AssistantPoint {
        val d=b-a;val n=d.dot(d);require(n>=1e-12) { "无法在重合控制点上吸附" }
        var t=(point-a).dot(d)/n;if(finite)t=t.coerceIn(0.0,1.0)
        return a+d*t
    }
    /** Freeze the guide and stroke origin. Parallel/vanishing/concentric rules depend on the origin. */
    class Projection(source: JSONObject,val start: AssistantPoint) {
        val assistant=normalize(source)
        val id=assistant.getString("id")
        private val type=assistant.getString("type")
        private val h=points(assistant)
        private val oval=if(h.size==3) ellipse(assistant) else null
        val radiusScale: Double = if(type=="concentric_ellipse") {
            val e=requireNotNull(oval);val d=start-e.center
            hypot(d.dot(e.axis)/e.a,d.dot(e.normal)/e.b).also { require(it>1e-8) { "不能从同心椭圆中心起笔" } }
        } else 1.0
        init { if(type=="vanishing_point") require((start-h[0]).length()>=0.01) { "不能从消失点本身起笔" } }
        fun project(p: AssistantPoint): AssistantPoint {
            val result=when(type) {
                "ruler" -> line(p,h[0],h[1],true)
                "infinite_ruler" -> line(p,h[0],h[1])
                "parallel_ruler" -> line(p,start,start+(h[1]-h[0]))
                "vanishing_point" -> line(p,h[0],start)
                else -> {
                    val e=requireNotNull(oval);val d=p-e.center
                    val u=d.dot(e.axis);val v=d.dot(e.normal)
                    val angle=if(abs(u)+abs(v)<1e-12) {
                        if(e.a<=e.b) 0.0 else PI/2.0
                    } else atan2(v/e.b,u/e.a)
                    e.center+e.axis*(e.a*radiusScale*cos(angle))+e.normal*(e.b*radiusScale*sin(angle))
                }
            }
            require(result.x.isFinite() && result.y.isFinite() && abs(result.x)<=1_000_000.0 && abs(result.y)<=1_000_000.0) { "吸附结果超出可编辑范围" }
            return result
        }
    }
    class SnapSession(state: JSONObject,val start: AssistantPoint,val tolerance: Double,allowedTypes:Set<String> = types.keys) {
        private val options=settings(state)
        private val candidates=items(state).filter { a ->
            a.getString("type") in allowedTypes && a.getBoolean("visible") && a.getBoolean("enabled") &&
                (!options.getBoolean("onlySelected") || a.getString("id")==selected(state)) &&
                !(a.getString("type")=="vanishing_point" && (start-points(a)[0]).length()<0.01) &&
                !(a.getString("type")=="concentric_ellipse" && (start-ellipse(a).center).length()<0.01)
        }.map { Projection(it,start) }.filter { (it.project(start)-start).length()<=tolerance }
        private var decided=false
        var active: Projection?=null;private set
        fun project(p: AssistantPoint): AssistantPoint {
            if(!decided && (p-start).length()>max(0.01,tolerance/8.0)) {
                active=candidates.minByOrNull { (it.project(p)-p).length() }
                decided=true
            }
            return active?.project(p) ?: p
        }
        fun complete(points: List<AssistantPoint>): List<AssistantPoint> {
            if(!decided) points.forEach { project(it) }
            val guide=active ?: return points
            return points.map(guide::project)
        }
    }
    private fun screen(p: AssistantPoint,m: Matrix): AssistantPoint {
        val v=floatArrayOf(p.x.toFloat(),p.y.toFloat());m.mapPoints(v)
        return AssistantPoint(v[0].toDouble(),v[1].toDouble())
    }
    fun geometryValid(a: JSONObject): Boolean {
        val p=points(a)
        if(p.size>=2 && (p[1]-p[0]).length()<0.01)return false
        if(p.size==3) {
            val c=(p[0]+p[1])*0.5;val major=(p[1]-p[0]).length()*0.5
            val axis=(p[1]-p[0])*(1.0/(2*major));val n=AssistantPoint(-axis.y,axis.x);val d=p[2]-c
            val divisor=1.0-d.dot(axis).pow(2)/major.pow(2)
            if(divisor<=1e-12)return false
            val minor=abs(d.dot(n))/sqrt(divisor)
            if(minor<0.01 || !minor.isFinite())return false
            val rx=hypot(major*axis.x,minor*n.x);val ry=hypot(major*axis.y,minor*n.y)
            if(abs(c.x)+rx>1_000_000 || abs(c.y)+ry>1_000_000)return false
        }
        return true
    }
    fun path(a: JSONObject): Path {
        val p=points(a);return Path().apply {
            if(p.size==3) {
                val e=ellipse(a);for(i in 0..128) {
                    val t=2*PI*i/128.0;val v=e.center+e.axis*(e.a*cos(t))+e.normal*(e.b*sin(t))
                    if(i==0)moveTo(v.x.toFloat(),v.y.toFloat()) else lineTo(v.x.toFloat(),v.y.toFloat())
                };close()
            } else if(p.size==2) {
                moveTo(p[0].x.toFloat(),p[0].y.toFloat());lineTo(p[1].x.toFloat(),p[1].y.toFloat())
            }
        }
    }
    fun draw(canvas: Canvas,state: JSONObject,m: Matrix,editing: Boolean=false,preview: JSONObject?=null,density: Float=1f) {
        val showGuides=settings(state).getBoolean("visible")
        if(!showGuides && !editing)return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=1.5f*density }
        fun infinite(a: AssistantPoint,d: AssistantPoint) {
            var lo=Double.NEGATIVE_INFINITY;var hi=Double.POSITIVE_INFINITY
            for((v,s,max) in listOf(Triple(d.x,a.x,canvas.width.toDouble()),Triple(d.y,a.y,canvas.height.toDouble()))) {
                if(abs(v)<1e-12) { if(s<0 || s>max)return }
                else { val t0=-s/v;val t1=(max-s)/v;lo=maxOf(lo,minOf(t0,t1));hi=minOf(hi,maxOf(t0,t1)) }
            }
            if(lo<=hi && lo.isFinite() && hi.isFinite()) {
                val x=a+d*lo;val y=a+d*hi;canvas.drawLine(x.x.toFloat(),x.y.toFloat(),y.x.toFloat(),y.y.toFloat(),paint)
            }
        }
        for(original in items(state)) {
            val a=if(preview!=null && preview.getString("id")==original.getString("id")) preview else original
            if(!a.getBoolean("visible"))continue
            paint.color=if(a.getBoolean("enabled")) Color.rgb(80,184,238) else Color.rgb(140,140,140)
            paint.alpha=if(a.getString("id")==selected(state)) 230 else 150
            val p=points(a);val h=p.map { screen(it,m) };val type=a.getString("type")
            if(showGuides) {
                if(type=="infinite_ruler" || type=="parallel_ruler") infinite(h[0],h[1]-h[0])
                else if(type=="vanishing_point") {
                    for(i in 0 until a.getInt("rays")) {
                        val t=PI*i/a.getInt("rays");val end=screen(p[0]+AssistantPoint(cos(t),sin(t)),m)
                        infinite(h[0],end-h[0])
                    }
                } else if(geometryValid(a)) { val path=path(a);path.transform(m);canvas.drawPath(path,paint) }
                if(type=="ruler" && a.getInt("subdivisions")>0 && geometryValid(a)) {
                    val d=h[1]-h[0];val length=d.length()
                    val n=AssistantPoint(-d.y,d.x)*(5*density/length);val count=a.getInt("subdivisions")
                    if(length/count>=4*density) for(i in 0..count) {
                        val c=h[0]+d*(i.toDouble()/count);val u=c-n;val v=c+n
                        canvas.drawLine(u.x.toFloat(),u.y.toFloat(),v.x.toFloat(),v.y.toFloat(),paint)
                    }
                }
            }
            if(editing) for(v in h) {
                paint.alpha=255;canvas.drawCircle(v.x.toFloat(),v.y.toFloat(),7*density,paint)
                // Geometry-only handles also work in the resident renderer without native font dependencies.
            }
        }
    }
}
