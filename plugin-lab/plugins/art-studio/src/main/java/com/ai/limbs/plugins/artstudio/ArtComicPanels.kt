package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Exact straight-edge edits. Curved outlines are rejected, never rasterized or flattened. */
internal object ArtComicPanels {
    const val MAX_VERTICES=256
    private const val EPS=0.00001
    val presets=linkedMapOf("thick" to "厚间隙","thin" to "薄间隙","special" to "特殊间隙")
    val pending=listOf("曲线边框、孔洞与凹多边形切分","同一轮廓内部间隙合并","非平行边之间的智能合并","物理单位和宽度预设文件")
    fun defaults()=JSONObject().put("mode","cut").put("automatic",true).put("preset","thick")
        .put("thick",12.0).put("thin",6.0).put("special",8.0).put("horizontal","thick")
        .put("vertical","thin").put("diagonal","special").put("angle",15.0).put("selectedOnly",false)
    fun options(p: JSONObject): JSONObject {
        val o=defaults();o.keys().forEach {if(p.has(it))o.put(it,p.get(it))}
        require(o.getString("mode") in setOf("cut","merge"))
        for(key in listOf("preset","horizontal","vertical","diagonal"))require(o.getString(key) in presets)
        for(key in presets.keys)require(o.getDouble(key).isFinite() && o.getDouble(key) in 0.0..512.0) {"间隙宽度需要在0–512像素之间"}
        require(o.getDouble("angle").isFinite() && o.getDouble("angle") in 0.0..45.0)
        o.getBoolean("automatic");o.getBoolean("selectedOnly")
        return o
    }
    fun info()=JSONObject().put("defaults",defaults()).put("pending",JSONArray(pending))
        .put("coordinateSpace","document").put("unit","px").put("maxVertices",MAX_VERTICES)
        .put("cutScope","convex straight closed outlines").put("mergeScope","two simple outlines with parallel facing edges")
        .put("source","plugins/tools/tool_knife/KisToolKnife.cpp")
    data class Point(val x: Double,val y: Double) {
        operator fun plus(p: Point)=Point(x+p.x,y+p.y)
        operator fun minus(p: Point)=Point(x-p.x,y-p.y)
        operator fun times(k: Double)=Point(x*k,y*k)
    }
    private fun dot(a: Point,b: Point)=a.x*b.x+a.y*b.y
    private fun cross(a: Point,b: Point)=a.x*b.y-a.y*b.x
    private fun length(a: Point)=hypot(a.x,a.y)
    private fun near(a: Point,b: Point)=length(a-b)<=EPS
    private fun area(q: List<Point>): Double {
        val base=q.first()
        return q.indices.sumOf {cross(q[it]-base,q[(it+1)%q.size]-base)}/2
    }
    private fun clean(raw: List<Point>): List<Point> {
        val q=mutableListOf<Point>()
        raw.forEach {if(q.isEmpty() || !near(q.last(),it))q.add(it)}
        if(q.size>1 && near(q.first(),q.last()))q.removeAt(q.lastIndex)
        var changed=true
        while(changed && q.size>=3) {
            changed=false
            for(i in q.indices) {
                val a=q[(i+q.size-1)%q.size];val b=q[i];val c=q[(i+1)%q.size]
                if(abs(cross(b-a,c-b))<=EPS*(length(b-a)+length(c-b)) && dot(b-a,c-b)>=0) {
                    q.removeAt(i);changed=true;break
                }
            }
        }
        return q
    }
    private fun onEdge(p: Point,a: Point,b: Point)=abs(cross(b-a,p-a))<=EPS*length(b-a) &&
        dot(p-a,b-a)>=-EPS && dot(p-b,a-b)>=-EPS
    private fun segmentTouch(a: Point,b: Point,c: Point,d: Point): Boolean {
        if(onEdge(a,c,d)||onEdge(b,c,d)||onEdge(c,a,b)||onEdge(d,a,b))return true
        return cross(b-a,c-a)*cross(b-a,d-a)<0 && cross(d-c,a-c)*cross(d-c,b-c)<0
    }
    private fun simple(raw: List<Point>): List<Point> {
        val q=clean(raw)
        require(q.size in 3..MAX_VERTICES && abs(area(q))>EPS) {"直边轮廓需要3–256顶点和非零面积"}
        for(i in q.indices)for(j in i+1 until q.size) {
            if(j==i+1 || i==0 && j==q.lastIndex)continue
            require(!segmentTouch(q[i],q[(i+1)%q.size],q[j],q[(j+1)%q.size])) {"轮廓自交或带孔，当前不能分格"}
        }
        return if(area(q)>0)q else q.reversed()
    }
    private fun convex(q: List<Point>)=q.indices.all {
        cross(q[(it+1)%q.size]-q[it],q[(it+2)%q.size]-q[(it+1)%q.size])>=-EPS
    }
    private fun inside(q: List<Point>,p: Point): Boolean {
        var yes=false
        for(i in q.indices) {
            val a=q[i];val b=q[(i+1)%q.size]
            if(onEdge(p,a,b))return false // endpoints on an edge are ambiguous, not interior.
            if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)yes=!yes
        }
        return yes
    }
    private fun json(q: List<Point>)=JSONArray().apply {q.forEach {put(JSONArray().put(it.x).put(it.y))}}
    private fun map(q: List<Point>,m: Matrix): List<Point> {
        val a=ArtShapes.encode(m);val v=(0 until 6).map {a.getDouble(it)}
        return q.map {Point(v[0]*it.x+v[2]*it.y+v[4],v[1]*it.x+v[3]*it.y+v[5])}
    }
    private fun transform(state: JSONObject,layer: JSONObject,shape: JSONObject)=
        ArtShapes.layerMatrix(state,layer).apply {preConcat(ArtShapes.matrix(shape.getJSONArray("matrix")))}
    private fun raw(shape: JSONObject): List<Point>? {
        val a=shape.getJSONArray("points")
        fun at(i: Int): Point {val p=a.getJSONArray(i);return Point(p.getDouble(0),p.getDouble(1))}
        return when(shape.getString("kind")) {
            "rectangle" -> {
                val p=at(0);val r=at(1);val x=min(p.x,r.x);val y=min(p.y,r.y);val u=max(p.x,r.x);val v=max(p.y,r.y)
                listOf(Point(x,y),Point(u,y),Point(u,v),Point(x,v))
            }
            "polygon" -> if(a.length()<=MAX_VERTICES)(0 until a.length()).map(::at) else null
            "path" -> {
                val commands=shape.getJSONArray("commands")
                if(!shape.getBoolean("closed") || a.length()>MAX_VERTICES+1 ||
                    (0 until commands.length()).any {commands.getString(it)!="L"})null
                else (0 until a.length()).map(::at)
            }
            else -> null
        }
    }
    fun target(state: JSONObject,layerId: String): JSONObject {
        val layer=ArtShapes.layer(state,layerId);ArtShapes.items(layer)
        require(ArtShapes.visible(state,layer) && !ArtMenuOperations.isLocked(state,layer)) {"分格需要可见且未锁定的矢量图层"}
        return layer
    }
    private data class Panel(val shape: JSONObject,val q: List<Point>,val matrix: Matrix)
    private fun candidates(state: JSONObject,layer: JSONObject,p: JSONObject,start: Point,end: Point): List<Panel> {
        val all=ArtShapes.items(layer)
        val ids=if(p.has("ids"))ArtShapes.ids(p.getJSONArray("ids")) else null
        if(ids!=null)require(ids.isNotEmpty() && ids.distinct().size==ids.size &&
            ids.all {id->all.any {it.getString("id")==id}}) {"指定对象不存在或列表为空"}
        val result=mutableListOf<Panel>()
        for(shape in all) {
            if(ids!=null && shape.getString("id") !in ids)continue
            if(!shape.getBoolean("visible") || shape.getDouble("opacity")==0.0)continue
            val m=transform(state,layer,shape)
            val b=ArtShapes.bounds(shape);ArtShapes.layerMatrix(state,layer).mapRect(b)
            // Conservative bounds gate keeps unsupported geometry out of an exact straight-edge operation.
            if(max(start.x,end.x)<b.left || min(start.x,end.x)>b.right ||
                max(start.y,end.y)<b.top || min(start.y,end.y)>b.bottom)continue
            val outline=raw(shape)
            require(outline!=null) {"手势范围包含曲线、开放路径或超过256顶点的对象；请用形状选择限定直边对象"}
            result.add(Panel(shape,simple(map(outline,m)),m))
        }
        return result
    }
    private data class Hit(val edge: Int,val t: Double,val point: Point,val corner: Boolean)
    private fun hits(q: List<Point>,a: Point,b: Point): List<Hit> {
        val v=b-a;val out=mutableListOf<Hit>()
        for(i in q.indices) {
            val c=q[i];val d=q[(i+1)%q.size];val e=d-c;val det=cross(v,e)
            if(abs(det)<=EPS*max(1.0,length(v)*length(e))) {
                require(!(onEdge(c,a,b)||onEdge(d,a,b)||onEdge(a,c,d)||onEdge(b,c,d))) {"切线沿着边框，无法确定分格方向"}
                continue
            }
            val t=cross(c-a,e)/det;val u=cross(c-a,v)/det
            if(t>=-EPS && t<=1+EPS && u>=-EPS && u<=1+EPS) {
                val p=a+v*t
                if(out.none {near(it.point,p)})out.add(Hit(i,t,p,u<=EPS||u>=1-EPS))
            }
        }
        return out.sortedBy {it.t}
    }
    private fun point(p: JSONArray): Point {
        require(p.length()==2)
        val q=Point(p.getDouble(0),p.getDouble(1))
        require(q.x.isFinite() && q.y.isFinite() && abs(q.x)<=1000000 && abs(q.y)<=1000000)
        return q
    }
    private fun line(p: JSONObject): Pair<Point,Point> {
        val a=point(p.getJSONArray("start"));val b=point(p.getJSONArray("end"))
        require(length(b-a)>=0.1) {"请拖出一条分格线"}
        return a to b
    }
    fun width(p: JSONObject,start: Point,end: Point): Double {
        val o=options(p);val degrees=abs(Math.toDegrees(atan2(end.y-start.y,end.x-start.x)))%180
        val horizontal=min(degrees,180-degrees);val vertical=abs(degrees-90)
        val preset=if(!o.getBoolean("automatic"))o.getString("preset")
            else if(horizontal<=o.getDouble("angle"))o.getString("horizontal")
            else if(vertical<=o.getDouble("angle"))o.getString("vertical") else o.getString("diagonal")
        return o.getDouble(preset)
    }
    private fun clip(q: List<Point>,origin: Point,normal: Point,offset: Double): List<Point> {
        val out=mutableListOf<Point>()
        for(i in q.indices) {
            val a=q[i];val b=q[(i+1)%q.size]
            val da=dot(a-origin,normal)-offset;val db=dot(b-origin,normal)-offset
            if(da>=0)out.add(a)
            if((da>=0)!=(db>=0))out.add(a+(b-a)*(da/(da-db)))
        }
        return clean(out)
    }
    private fun polygon(panel: Panel,q: List<Point>): JSONObject {
        val inverse=Matrix();require(panel.matrix.invert(inverse)) {"分格对象变换不可逆"}
        val local=map(simple(q),inverse)
        val shape=JSONObject(panel.shape.toString()).put("id",UUID.randomUUID().toString())
            .put("kind","polygon").put("points",json(local))
        for(key in listOf("commands","closed","nodeModes"))shape.remove(key)
        return ArtShapes.normalize(shape)
    }
    private fun replacement(layerId: String,removed: List<String>,groups: List<Pair<String,List<JSONObject>>>): JSONObject =
        JSONObject().put("layerId",layerId).put("removedIds",JSONArray(removed))
            .put("groups",JSONArray().apply {groups.forEach {(anchor,shapes)->
                put(JSONObject().put("anchor",anchor).put("shapes",JSONArray(shapes)))}})
    fun cut(state: JSONObject,p: JSONObject): JSONObject? {
        val layerId=p.getString("layerId");val layer=target(state,layerId);val (a,b)=line(p);val w=width(p,a,b)
        val v=b-a;val n=Point(-v.y/length(v),v.x/length(v));val removed=mutableListOf<String>()
        val groups=mutableListOf<Pair<String,List<JSONObject>>>()
        for(panel in candidates(state,layer,p,a,b)) {
            val intersections=hits(panel.q,a,b)
            if(intersections.size<2)continue
            require(!panel.shape.getBoolean("locked")) {"分格对象已锁定"}
            require(convex(panel.q)) {"凹轮廓切分尚未实现，请使用凸多边形分格"}
            require(intersections.size==2 && !inside(panel.q,a) && !inside(panel.q,b)) {"分格线需完整穿过边框"}
            val left=clip(panel.q,a,n,w/2);val right=clip(panel.q,a,n*(-1.0),w/2)
            require(left.size>=3 && right.size>=3 && abs(area(left))>EPS && abs(area(right))>EPS) {"间隙过宽或切线太靠近边缘，请调整后重试"}
            val id=panel.shape.getString("id");removed.add(id);groups.add(id to listOf(polygon(panel,left),polygon(panel,right)))
        }
        if(removed.isEmpty())return null
        return replacement(layerId,removed,groups).put("gutterWidth",w)
    }
    private fun walk(q: List<Point>,edge: Int,from: Point,to: Point): List<Point> {
        val out=mutableListOf(from)
        for(step in 1..q.size)out.add(q[(edge+step)%q.size])
        out.add(to);return out
    }
    fun merge(state: JSONObject,p: JSONObject): JSONObject {
        val layerId=p.getString("layerId");val layer=target(state,layerId);val (a,b)=line(p)
        val panels=candidates(state,layer,p,a,b)
        val crossed=panels.flatMap {panel->hits(panel.q,a,b).map {panel to it}}
        require(crossed.size==2 && crossed[0].first!==crossed[1].first) {"请从一个格内部拖到另一个格内部，只穿过一条间隙的两条边"}
        val first=crossed.firstOrNull {inside(it.first.q,a)} ?: error("合并线起点需在第一格内部")
        val second=crossed.firstOrNull {inside(it.first.q,b)} ?: error("合并线终点需在第二格内部")
        require(first.first!==second.first && !first.second.corner && !second.second.corner) {"合并线不能经过分格顶点"}
        val x=first.first;val y=second.first
        require(!x.shape.getBoolean("locked") && !y.shape.getBoolean("locked")) {"分格对象已锁定"}
        val i=first.second.edge;val j=second.second.edge
        val x0=x.q[i];val x1=x.q[(i+1)%x.q.size];val y0=y.q[j];val y1=y.q[(j+1)%y.q.size]
        val t=(x1-x0)*(1/length(x1-x0));val u=(y1-y0)*(1/length(y1-y0))
        require(abs(cross(t,u))<=0.00001 && dot(t,u)<-0.99999) {"当前只支持相邻平行直边合并"}
        val gap=-cross(t,y0-x0)
        require(gap>EPS && gap<=512.0) {"间隙方向或宽度不符合相邻分格"}
        val lo=max(0.0,min(dot(y0-x0,t),dot(y1-x0,t)))
        val hi=min(length(x1-x0),max(dot(y0-x0,t),dot(y1-x0,t)))
        require(hi-lo>EPS) {"两条边没有相对重叠的区间"}
        val normal=Point(t.y,-t.x)
        val xLo=x0+t*lo;val xHi=x0+t*hi;val yLo=xLo+normal*gap;val yHi=xHi+normal*gap
        // Stitch the two exterior walks; the bridge fills only the overlapping gutter, never a convex hull.
        val merged=simple(walk(x.q,i,xHi,xLo)+walk(y.q,j,yLo,yHi))
        val expected=abs(area(x.q))+abs(area(y.q))+(hi-lo)*gap
        require(abs(abs(area(merged))-expected)<=max(0.01,expected*0.00001)) {"合并轮廓发生重叠"}
        val bridge=listOf(xLo,xHi,yHi,yLo)
        val center=(xLo+yHi)*0.5
        val minX=bridge.minOf {it.x};val maxX=bridge.maxOf {it.x}
        val minY=bridge.minOf {it.y};val maxY=bridge.maxOf {it.y}
        for(other in ArtShapes.items(layer)) {
            if(other.getString("id") in setOf(x.shape.getString("id"),y.shape.getString("id")) ||
                !other.getBoolean("visible") || other.getDouble("opacity")==0.0)continue
            val bounds=ArtShapes.bounds(other);ArtShapes.layerMatrix(state,layer).mapRect(bounds)
            if(bounds.right<=minX+EPS || bounds.left>=maxX-EPS || bounds.bottom<=minY+EPS || bounds.top>=maxY-EPS)continue
            val outline=raw(other) ?: error("待合并间隙附近有尚不支持的轮廓，请先移开该对象")
            val q=simple(map(outline,transform(state,layer,other)))
            fun crossing(a: Point,b: Point,c: Point,d: Point): Boolean {
                val ab1=cross(b-a,c-a);val ab2=cross(b-a,d-a)
                val cd1=cross(d-c,a-c);val cd2=cross(d-c,b-c)
                return ab1*ab2< -EPS && cd1*cd2< -EPS
            }
            require(!inside(q,center) && q.none {inside(bridge,it)} && bridge.none {inside(q,it)} &&
                bridge.indices.none {k->q.indices.any {l->
                    crossing(bridge[k],bridge[(k+1)%4],q[l],q[(l+1)%q.size])}}) {"待合并间隙与第三个格重叠"}
        }
        val all=ArtShapes.items(layer);val reference=if(all.indexOf(x.shape)<=all.indexOf(y.shape))x else y
        val shape=polygon(reference,merged)
        val removed=listOf(x.shape.getString("id"),y.shape.getString("id"))
        return replacement(layerId,removed,listOf(reference.shape.getString("id") to listOf(shape)))
    }
    fun frame(state: JSONObject,p: JSONObject): JSONObject {
        val layer=target(state,p.getString("layerId"))
        val x=p.getDouble("x");val y=p.getDouble("y");val w=p.getDouble("width");val h=p.getDouble("height")
        require(listOf(x,y,w,h).all {it.isFinite()} && w>0 && h>0) {"分格框尺寸无效"}
        val inverse=Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse))
        val q=map(listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h)),inverse)
        val shape=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","polygon").put("points",json(q))
            .put("fill","#00000000").put("stroke","#FF161616").put("strokeWidth",2.0).put("opacity",1.0)
        p.optJSONObject("style")?.let {style->
            require(style.keys().asSequence().all {it in setOf("fill","stroke","strokeWidth","opacity")})
            style.keys().forEach {shape.put(it,style.get(it))}
        }
        return ArtShapes.normalize(shape)
    }
    /** Replay stores normalized geometry once, preserving shape stacking and object transforms. */
    fun apply(state: JSONObject,p: JSONObject) {
        val layer=target(state,p.getString("layerId"));val all=ArtShapes.items(layer)
        val removed=ArtShapes.ids(p.getJSONArray("removedIds"))
        require(removed.isNotEmpty() && removed.distinct().size==removed.size && removed.all {id->all.any {it.getString("id")==id}})
        require(all.filter {it.getString("id") in removed}.none {it.getBoolean("locked")})
        val groups=p.getJSONArray("groups");require(groups.length() in 1..removed.size)
        val additions=linkedMapOf<String,List<JSONObject>>()
        for(i in 0 until groups.length()) {
            val group=groups.getJSONObject(i);val anchor=group.getString("anchor")
            require(anchor in removed && anchor !in additions)
            val shapes=group.getJSONArray("shapes");require(shapes.length() in 1..2)
            additions[anchor]=(0 until shapes.length()).map {ArtShapes.normalize(shapes.getJSONObject(it))}
        }
        val next=all.flatMap {shape->val id=shape.getString("id")
            if(id in removed)additions[id].orEmpty() else listOf(shape)}
        require(next.size<=512 && next.sumOf {it.getJSONArray("points").length()}<=32768) {"分格超过矢量层对象或顶点预算"}
        require(next.map {it.getString("id")}.distinct().size==next.size)
        val created=additions.values.flatten().map {it.getString("id")}
        require(created.none {id->all.any {it.getString("id")==id}})
        layer.put("shapes",JSONArray(next))
        state.put("selectedLayerId",layer.getString("id")).put("shapeSelection",
            JSONObject().put("layerId",layer.getString("id")).put("ids",JSONArray(created)))
    }
}
