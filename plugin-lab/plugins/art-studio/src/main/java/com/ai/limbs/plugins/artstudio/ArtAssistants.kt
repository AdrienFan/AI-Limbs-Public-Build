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
        "vanishing_point" to "消失点", "spline" to "样条", "perspective_grid" to "透视网格",
        "perspective_ellipse" to "透视椭圆", "two_vanishing_points" to "双消失点",
        "fisheye" to "鱼眼", "curvilinear_perspective" to "曲线透视")
    val pending = emptyList<String>()
    val editableFields=setOf("points","name","visible","enabled","locked","subdivisions","rays",
        "localEnabled","localBounds","fixedLength","lengthUnit","unitDpi","useVertical")
    val pointHelp=mapOf("ruler" to "起点、终点", "infinite_ruler" to "两点确定方向", "parallel_ruler" to "两点确定平行方向",
        "ellipse" to "主轴两端、轴段内侧方一点", "concentric_ellipse" to "主轴两端、轴段内侧方一点",
        "vanishing_point" to "消失点", "spline" to "起点、终点、起点柄、终点柄",
        "perspective_grid" to "依次排列的四角，须构成凸四边形", "perspective_ellipse" to "依次排列的四角，须构成凸四边形",
        "two_vanishing_points" to "左消失点、右消失点、预览中心", "fisheye" to "轴两端、轴段内侧方一点",
        "curvilinear_perspective" to "两个消失点")
    fun typeInfo()=JSONObject().apply { types.forEach { (type,label)->
        val example=when(type) {
            "spline"->listOf(AssistantPoint(20.0,100.0),AssistantPoint(280.0,100.0),AssistantPoint(80.0,20.0),AssistantPoint(220.0,180.0))
            "perspective_grid","perspective_ellipse"->listOf(AssistantPoint(20.0,20.0),AssistantPoint(280.0,40.0),AssistantPoint(220.0,220.0),AssistantPoint(40.0,200.0))
            "two_vanishing_points"->listOf(AssistantPoint(20.0,80.0),AssistantPoint(280.0,80.0),AssistantPoint(150.0,150.0))
            "ellipse","concentric_ellipse","fisheye"->listOf(AssistantPoint(20.0,100.0),AssistantPoint(280.0,100.0),AssistantPoint(150.0,160.0))
            "vanishing_point"->listOf(AssistantPoint(150.0,80.0))
            else->listOf(AssistantPoint(20.0,100.0),AssistantPoint(280.0,100.0))
        }
        put(type,JSONObject().put("label",label).put("pointCount",count(type)).put("pointOrder",pointHelp.getValue(type))
            .put("examplePoints",JSONArray(example.map {it.json()})).put("maxRays",if(type in setOf("fisheye","curvilinear_perspective"))16 else 64))
        }
    }
    val brushTools = setOf("ink","pencil","soft","spray","eraser","calligraphy","mirror","dyna","line")
    fun count(type: String) = when(type) {
        "vanishing_point" -> 1
        "ellipse","concentric_ellipse","two_vanishing_points","fisheye" -> 3
        "spline","perspective_grid","perspective_ellipse" -> 4
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
        var p=points(a)
        if(p.size>=2) require((p[1]-p[0]).length()>=0.01) { "尺规两端不能重合" }
        for(key in listOf("fixedLength","unitDpi")) if(a.has(key))require(a.get(key) is Number)
        val length=a.optDouble("fixedLength",0.0);val unit=a.optString("lengthUnit","px");val dpi=a.optDouble("unitDpi",96.0)
        if(a.has("lengthUnit"))require(a.get("lengthUnit") is String)
        val pixels=ArtAssistantGeometry.pixels(length,unit,dpi)
        require(length==0.0 || (type=="ruler" && pixels in 0.01..1_000_000.0)) { "固定长度仅适用于直尺，换算后须为0.01–1000000像素；0为自由长度" }
        if(length>0) {
            p=listOf(p[0],p[0]+(p[1]-p[0])*(pixels/(p[1]-p[0]).length()))
            require(p.all {abs(it.x)<=1_000_000&&abs(it.y)<=1_000_000}) {"固定长度端点超出可编辑范围"}
            a.put("points",JSONArray(p.map {it.json()}))
        }
        a.put("fixedLength",length).put("lengthUnit",unit).put("unitDpi",dpi)
        require(geometryValid(a)) { "尺规几何无效；椭圆/鱼眼第三点须位于轴段内侧方" }
        for(key in listOf("visible","enabled","locked","localEnabled","useVertical")) if(a.has(key)) require(a.get(key) is Boolean) { "尺规开关必须为布尔值" }
        if(a.has("localBounds")) {
            val b=a.getJSONObject("localBounds")
            for(key in listOf("x","y","width","height"))require(b.get(key) is Number && b.getDouble(key).isFinite())
            require(abs(b.getDouble("x"))<=1_000_000&&abs(b.getDouble("y"))<=1_000_000 &&
                b.getDouble("width") in 0.01..2_000_000.0&&b.getDouble("height") in 0.01..2_000_000.0&&
                abs(b.getDouble("x")+b.getDouble("width"))<=1_000_000&&abs(b.getDouble("y")+b.getDouble("height"))<=1_000_000) {"局部作用区无效"}
        }
        require(!a.optBoolean("localEnabled",false)||a.has("localBounds")) {"启用局部作用区须提供localBounds"}
        a.put("localEnabled",a.optBoolean("localEnabled",false)).put("useVertical",a.optBoolean("useVertical",true))
        if(a.has("name"))require(a.get("name") is String)
        for(key in listOf("subdivisions","rays")) if(a.has(key)) require(a.get(key) is Number && a.getDouble(key)==a.getInt(key).toDouble())
        a.put("name",a.optString("name",types.getValue(type)).take(100))
        a.put("visible",a.optBoolean("visible",true)).put("enabled",a.optBoolean("enabled",true))
            .put("locked",a.optBoolean("locked",false))
        val sub=a.optInt("subdivisions",10); require(sub in 0..100)
        val rays=a.optInt("rays",16); require(rays in 4..(if(type in setOf("fisheye","curvilinear_perspective"))16 else 64))
        if(type=="perspective_grid")require(sub in 1..100)
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
                    require(keys.isNotEmpty() && keys.all { it in editableFields })
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
        private val oval=if(type in setOf("ellipse","concentric_ellipse")) ellipse(assistant) else null
        private val advanced=if(type in ArtAssistantGeometry.advanced)ArtAssistantGeometry.Projection(assistant,start) else null
        val radiusScale: Double = if(type=="concentric_ellipse") {
            val e=requireNotNull(oval);val d=start-e.center
            hypot(d.dot(e.axis)/e.a,d.dot(e.normal)/e.b).also { require(it>1e-8) { "不能从同心椭圆中心起笔" } }
        } else 1.0
        init { require(ArtAssistantGeometry.eligible(assistant,start)) {"起笔不在尺规作用区内，或位于退化位置"} }
        fun resetTracking() {advanced?.resetTracking()}
        fun project(p: AssistantPoint): AssistantPoint {
            val result=if(advanced!=null)advanced.project(p) else when(type) {
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
                ArtAssistantGeometry.eligible(a,start)
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
            guide.resetTracking()
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
        if(a.getString("type") in setOf("ellipse","concentric_ellipse","fisheye")) {
            val c=(p[0]+p[1])*0.5;val major=(p[1]-p[0]).length()*0.5
            val axis=(p[1]-p[0])*(1.0/(2*major));val n=AssistantPoint(-axis.y,axis.x);val d=p[2]-c
            val divisor=1.0-d.dot(axis).pow(2)/major.pow(2)
            if(divisor<=1e-12)return false
            val minor=abs(d.dot(n))/sqrt(divisor)
            if(minor<0.01 || !minor.isFinite())return false
            val rx=hypot(major*axis.x,minor*n.x);val ry=hypot(major*axis.y,minor*n.y)
            if(abs(c.x)+rx>1_000_000 || abs(c.y)+ry>1_000_000)return false
        }
        if(a.getString("type") in setOf("perspective_grid","perspective_ellipse")) {
            return ArtAssistantGeometry.Quad.valid(p)
        }
        return true
    }
    fun localCorners(a:JSONObject):List<AssistantPoint> {
        if(!a.optBoolean("localEnabled",false))return emptyList()
        val b=a.getJSONObject("localBounds");val x=b.getDouble("x");val y=b.getDouble("y")
        return listOf(AssistantPoint(x,y),AssistantPoint(x+b.getDouble("width"),y+b.getDouble("height")))
    }
    fun guides(a:JSONObject):List<ArtAssistantGeometry.Guide> {
        if(a.getString("type") in ArtAssistantGeometry.advanced)return ArtAssistantGeometry.guides(a)
        val h=points(a)
        return when(a.getString("type")) {
            "ellipse","concentric_ellipse"->{val e=ellipse(a)
                listOf(ArtAssistantGeometry.Guide((0..128).map {i->val t=2*PI*i/128.0
                    e.center+e.axis*(e.a*cos(t))+e.normal*(e.b*sin(t))}))}
            "vanishing_point"->(0 until a.getInt("rays")).map {i->val t=PI*i/a.getInt("rays")
                ArtAssistantGeometry.Guide(listOf(h[0],h[0]+AssistantPoint(cos(t),sin(t))),true)}
            else->listOf(ArtAssistantGeometry.Guide(h,a.getString("type")!="ruler"))
        }
    }
    fun hitDistance(a:JSONObject,point:AssistantPoint,m:Matrix):Double {
        if(!geometryValid(a))return Double.POSITIVE_INFINITY
        if(!ArtAssistantGeometry.localEligible(a,point))return Double.POSITIVE_INFINITY
        val target=screen(point,m)
        return guides(a).minOf {guide->val p=guide.points.map {screen(it,m)}
            (0 until p.lastIndex).minOf {i->
                val d=p[i+1]-p[i];val squared=d.dot(d)
                if(squared<1e-12)(target-p[i]).length() else {
                    val t=(target-p[i]).dot(d)/squared
                    val projection=p[i]+d*(if(guide.infinite)t else t.coerceIn(0.0,1.0))
                    (projection-target).length()
                }
            }
        }
    }
    fun path(a: JSONObject): Path {
        return Path().apply {for(guide in guides(a).filterNot {it.infinite}) {
            guide.points.forEachIndexed {i,v->if(i==0)moveTo(v.x.toFloat(),v.y.toFloat()) else lineTo(v.x.toFloat(),v.y.toFloat())}
        }}
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
                if(geometryValid(a)) {
                    canvas.save()
                    val corners=localCorners(a).map {screen(it,m)}
                    if(corners.isNotEmpty()) {
                        val b=a.getJSONObject("localBounds");val rect=Path().apply {
                            addRect(b.getDouble("x").toFloat(),b.getDouble("y").toFloat(),
                                (b.getDouble("x")+b.getDouble("width")).toFloat(),(b.getDouble("y")+b.getDouble("height")).toFloat(),Path.Direction.CW)
                            transform(m)
                        };canvas.clipPath(rect)
                    }
                    for(guide in guides(a)) {
                        val vertices=guide.points.map {screen(it,m)}
                        if(guide.infinite)infinite(vertices[0],vertices[1]-vertices[0])
                        else {val path=Path();vertices.forEachIndexed {i,v->if(i==0)path.moveTo(v.x.toFloat(),v.y.toFloat()) else path.lineTo(v.x.toFloat(),v.y.toFloat())};canvas.drawPath(path,paint)}
                    }
                    if(type=="ruler" && a.getInt("subdivisions")>0) {
                        val d=h[1]-h[0];val length=d.length()
                        val n=AssistantPoint(-d.y,d.x)*(5*density/length);val count=a.getInt("subdivisions")
                        if(length/count>=4*density) for(i in 0..count) {
                            val c=h[0]+d*(i.toDouble()/count);val u=c-n;val v=c+n
                            canvas.drawLine(u.x.toFloat(),u.y.toFloat(),v.x.toFloat(),v.y.toFloat(),paint)
                        }
                    }
                    canvas.restore()
                }
            }
            val local=localCorners(a)
            if(local.isNotEmpty() && (showGuides||editing)) {
                val b=a.getJSONObject("localBounds");val boundary=Path().apply {
                    addRect(b.getDouble("x").toFloat(),b.getDouble("y").toFloat(),
                        (b.getDouble("x")+b.getDouble("width")).toFloat(),(b.getDouble("y")+b.getDouble("height")).toFloat(),Path.Direction.CW);transform(m)
                };canvas.drawPath(boundary,paint)
            }
            if(editing) for(v in h+local.map {screen(it,m)}) {
                paint.alpha=255;canvas.drawCircle(v.x.toFloat(),v.y.toFloat(),7*density,paint)
                // Geometry-only handles also work in the resident renderer without native font dependencies.
            }
        }
    }
}
