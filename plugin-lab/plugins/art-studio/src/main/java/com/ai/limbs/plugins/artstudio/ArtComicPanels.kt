package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Boolean contour editing in document space; persisted results are ordinary editable paths.
 * Curves are explicitly flattened to a bounded document-space tolerance, never rasterized.
 */
internal object ArtComicPanels {
    const val MAX_VERTICES=2048
    const val PATH_TOLERANCE=0.25f
    private const val EPS=0.00001
    val presets=linkedMapOf("thick" to "厚间隙","thin" to "薄间隙","special" to "特殊间隙")
    val pending=listOf("物理单位和宽度预设文件","保留原始三次曲线控制柄的精确布尔切分")
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
        .put("cutScope","closed rectangle/ellipse/polygon/path; concave, holes and multiple closed subpaths; finite gesture must span every crossed contour")
        .put("mergeScope","two facing edge segments, including nonparallel edges and two sides of a hole in one object; bridge only their shared projected interval, width <=512px, reject overlaps with other filled contours")
        .put("geometry","Path boolean operations; output closed polyline paths, document-space approximation tolerance 0.25px; <=2048 drawing segments and <=64 contours per result; original control handles are not retained")
        .put("source","plugins/tools/tool_knife/CutThroughShapeStrategy.cpp; RemoveGutterStrategy.cpp")
    data class Point(val x:Double,val y:Double) {
        operator fun plus(p:Point)=Point(x+p.x,y+p.y)
        operator fun minus(p:Point)=Point(x-p.x,y-p.y)
        operator fun times(k:Double)=Point(x*k,y*k)
    }
    private fun dot(a:Point,b:Point)=a.x*b.x+a.y*b.y
    private fun cross(a:Point,b:Point)=a.x*b.y-a.y*b.x
    private fun length(a:Point)=hypot(a.x,a.y)
    private fun near(a:Point,b:Point)=length(a-b)<=EPS
    private fun area(q:List<Point>):Double {
        if(q.size<3)return 0.0
        val base=q.first()
        return q.indices.sumOf {cross(q[it]-base,q[(it+1)%q.size]-base)}/2
    }
    private fun json(q:List<Point>)=JSONArray().apply {q.forEach {put(JSONArray().put(it.x).put(it.y))}}
    private fun map(q:List<Point>,m:Matrix):List<Point> {
        val a=ArtShapes.encode(m);val v=(0 until 6).map {a.getDouble(it)}
        return q.map {Point(v[0]*it.x+v[2]*it.y+v[4],v[1]*it.x+v[3]*it.y+v[5])}
    }
    private fun transform(state:JSONObject,layer:JSONObject,shape:JSONObject)=
        ArtShapes.layerMatrix(state,layer).apply {preConcat(ArtShapes.matrix(shape.getJSONArray("matrix")))}
    private fun polygonPath(q:List<Point>)=Path().apply {
        require(q.size>=3)
        moveTo(q[0].x.toFloat(),q[0].y.toFloat())
        q.drop(1).forEach {lineTo(it.x.toFloat(),it.y.toFloat())};close()
    }
    private fun boolean(a:Path,b:Path,op:Path.Op)=Path().also {
        check(it.op(a,b,op)) {"分格路径布尔运算失败，工程未写入"}
    }
    /** Repeated fractions mark moves between contours in Path.approximate(), including holes. */
    internal fun contours(path:Path):List<List<Point>> {
        val samples=path.approximate(PATH_TOLERANCE);val result=mutableListOf<List<Point>>()
        var current=mutableListOf<Point>()
        fun finish() {
            if(current.size>1 && near(current.first(),current.last()))current.removeAt(current.lastIndex)
            if(current.size>=3 && abs(area(current))>EPS)result.add(current.toList())
            current=mutableListOf()
        }
        var fraction=-1f;var previous:Point?=null
        for(i in samples.indices step 3) {
            val q=Point(samples[i+1].toDouble(),samples[i+2].toDouble())
            if(samples[i]==fraction && previous!=null && !near(previous,q))finish()
            if(current.isEmpty() || !near(current.last(),q))current.add(q)
            fraction=samples[i];previous=q
        }
        finish()
        require(result.size<=64 && result.sumOf {it.size}<=MAX_VERTICES) {"分格结果超过64条轮廓或2048段，请简化对象后重试"}
        return result
    }
    private fun nonempty(path:Path)=!path.isEmpty && contours(path).isNotEmpty()
    private fun contains(path:Path,p:Point)=ArtSelection.containsPath(path,p.x,p.y)
    fun target(state:JSONObject,layerId:String):JSONObject {
        val layer=ArtShapes.layer(state,layerId);ArtShapes.items(layer)
        require(ArtShapes.visible(state,layer) && !ArtMenuOperations.isLocked(state,layer)) {"分格需要可见且未锁定的矢量图层"}
        return layer
    }
    private data class Panel(val shape:JSONObject,val outline:Path,val contours:List<List<Point>>,val matrix:Matrix)
    private fun candidates(state:JSONObject,layer:JSONObject,p:JSONObject,a:Point,b:Point):List<Panel> {
        val all=ArtShapes.items(layer)
        val ids=if(p.has("ids"))ArtShapes.ids(p.getJSONArray("ids")) else null
        if(ids!=null)require(ids.isNotEmpty() && ids.distinct().size==ids.size && ids.all {id->all.any {it.getString("id")==id}}) {"指定对象不存在或列表为空"}
        val result=mutableListOf<Panel>()
        for(shape in all) {
            if(ids!=null && shape.getString("id") !in ids || !shape.getBoolean("visible") || shape.getDouble("opacity")==0.0)continue
            val matrix=transform(state,layer,shape)
            val source=ArtShapes.path(shape).apply {transform(matrix)}
            val outline=source
            val bounds=RectF();outline.computeBounds(bounds,true)
            if(max(a.x,b.x)<bounds.left || min(a.x,b.x)>bounds.right || max(a.y,b.y)<bounds.top || min(a.y,b.y)>bounds.bottom)continue
            require(shape.getString("kind")!="line" && (shape.getString("kind")!="path" || ArtPathTopology.parts(shape).all {it.closed})) {"分格只处理闭合轮廓；请用形状选择排除开放路径"}
            val canonical=boolean(outline,outline,Path.Op.UNION)
            val parts=contours(canonical)
            // Geometry tests and the Boolean operands must use the same sampled boundaries;
            // mixing exact curves with chord hits would reject valid bridges as overlapping.
            val sampled=Path().apply {fillType=canonical.fillType;parts.forEach {addPath(polygonPath(it))}}
            result.add(Panel(shape,sampled,parts,matrix))
        }
        return result
    }
    private data class Hit(val contour:Int,val edge:Int,val t:Double,val point:Point,val corner:Boolean)
    private fun hits(panel:Panel,a:Point,b:Point,bounded:Boolean=true):List<Hit> {
        val v=b-a;val out=mutableListOf<Hit>()
        panel.contours.forEachIndexed {contour,q->for(i in q.indices) {
            val c=q[i];val d=q[(i+1)%q.size];val e=d-c;val det=cross(v,e)
            if(abs(det)<=EPS*max(1.0,length(v)*length(e))) {
                require(abs(cross(c-a,v))>EPS*max(1.0,length(v))) {"切线与边框重合，请调整位置"}
                continue
            }
            val t=cross(c-a,e)/det;val u=cross(c-a,v)/det
            if((!bounded || t>=-EPS && t<=1+EPS) && u>=-EPS && u<=1+EPS) {
                val point=a+v*t
                if(out.none {near(it.point,point)})out.add(Hit(contour,i,t,point,u<=EPS||u>=1-EPS))
            }
        }}
        return out.sortedBy {it.t}
    }
    private fun point(p:JSONArray):Point {
        require(p.length()==2)
        val q=Point(p.getDouble(0),p.getDouble(1))
        require(q.x.isFinite() && q.y.isFinite() && abs(q.x)<=1000000 && abs(q.y)<=1000000)
        return q
    }
    private fun line(p:JSONObject):Pair<Point,Point> {
        val a=point(p.getJSONArray("start"));val b=point(p.getJSONArray("end"))
        require(length(b-a)>=0.1) {"请拖出一条分格线"};return a to b
    }
    fun width(p:JSONObject,start:Point,end:Point):Double {
        val o=options(p);val degrees=abs(Math.toDegrees(atan2(end.y-start.y,end.x-start.x)))%180
        val horizontal=min(degrees,180-degrees);val vertical=abs(degrees-90)
        val preset=if(!o.getBoolean("automatic"))o.getString("preset") else if(horizontal<=o.getDouble("angle"))o.getString("horizontal")
            else if(vertical<=o.getDouble("angle"))o.getString("vertical") else o.getString("diagonal")
        return o.getDouble(preset)
    }
    private fun resultShape(panel:Panel,path:Path):JSONObject {
        val inverse=Matrix();require(panel.matrix.invert(inverse)) {"分格对象变换不可逆"}
        val parts=contours(path).map {q->
            ArtPathTopology.Part(map(q,inverse).map {ArtPathGeometry.Node(ArtPathGeometry.Vec(it.x,it.y))}.toMutableList(),true)
        }
        require(parts.isNotEmpty()) {"分格结果面积为零"}
        val shape=JSONObject(panel.shape.toString()).put("id",UUID.randomUUID().toString()).put("kind","path")
        for(key in listOf("cornerRadius","calligraphy","nodeModes"))shape.remove(key)
        val geometry=ArtPathTopology.geometry(parts);geometry.keys().forEach {shape.put(it,geometry.get(it))}
        val style=ArtObjectStyle.settings(shape)
        style.put("fillRule",if(path.fillType==Path.FillType.EVEN_ODD)"evenodd" else "nonzero")
        shape.put("objectStyle",style).put("geometryEdited",true)
        return ArtShapes.normalize(shape)
    }
    private fun replacement(layerId:String,removed:List<String>,groups:List<Pair<String,List<JSONObject>>>):JSONObject =
        JSONObject().put("layerId",layerId).put("removedIds",JSONArray(removed))
            .put("groups",JSONArray().apply {groups.forEach {(anchor,shapes)->put(JSONObject().put("anchor",anchor).put("shapes",JSONArray(shapes)))}})
    fun cut(state:JSONObject,p:JSONObject):JSONObject? {
        val layerId=p.getString("layerId");val layer=target(state,layerId);val (a,b)=line(p);val width=width(p,a,b)
        val t=(b-a)*(1/length(b-a));val n=Point(-t.y,t.x)
        val removed=mutableListOf<String>();val groups=mutableListOf<Pair<String,List<JSONObject>>>()
        for(panel in candidates(state,layer,p,a,b)) {
            val intersections=hits(panel,a,b,false)
            if(intersections.size<2 || intersections.none {it.t in 0.0..1.0})continue
            require(!panel.shape.getBoolean("locked")) {"分格对象已锁定"}
            require(!contains(panel.outline,a) && !contains(panel.outline,b) && intersections.all {it.t>=-EPS && it.t<=1+EPS}) {"切线需完整穿过所有相交轮廓"}
            val all=panel.contours.flatten()
            val lo=all.minOf {dot(it-a,t)}-1;val hi=all.maxOf {dot(it-a,t)}+1
            val lower=all.minOf {dot(it-a,n)}-1;val upper=all.maxOf {dot(it-a,n)}+1
            fun side(low:Double,high:Double)=if(high<=low)Path() else polygonPath(listOf(a+t*lo+n*low,a+t*hi+n*low,a+t*hi+n*high,a+t*lo+n*high))
            val left=boolean(panel.outline,side(width/2,upper),Path.Op.INTERSECT)
            val right=boolean(panel.outline,side(lower,-width/2),Path.Op.INTERSECT)
            require(nonempty(left) && nonempty(right)) {"间隙过宽或切线太靠近边缘，请调整后重试"}
            val id=panel.shape.getString("id");removed.add(id)
            groups.add(id to listOf(resultShape(panel,left),resultShape(panel,right)))
        }
        if(removed.isEmpty())return null
        return replacement(layerId,removed,groups).put("gutterWidth",width).put("approximationPx",PATH_TOLERANCE.toDouble())
    }
    fun merge(state:JSONObject,p:JSONObject):JSONObject {
        val layerId=p.getString("layerId");val layer=target(state,layerId);val (a,b)=line(p)
        val panels=candidates(state,layer,p,a,b)
        val crossed=panels.flatMap {panel->hits(panel,a,b).map {panel to it}}.sortedBy {it.second.t}
        require(crossed.size==2) {"请从分格内部跨过一条间隙，仅穿过两条边"}
        val first=crossed[0];val second=crossed[1];val x=first.first;val y=second.first
        require(contains(x.outline,a) && contains(y.outline,b) && !first.second.corner && !second.second.corner) {"起终点须在分格内部，合并线不能经过顶点"}
        require(!x.shape.getBoolean("locked") && !y.shape.getBoolean("locked")) {"分格对象已锁定"}
        val q=x.contours[first.second.contour];val r=y.contours[second.second.contour]
        val x0=q[first.second.edge];val x1=q[(first.second.edge+1)%q.size]
        val y0=r[second.second.edge];val y1=r[(second.second.edge+1)%r.size]
        val u=(x1-x0)*(1/length(x1-x0));var v=(y1-y0)*(1/length(y1-y0))
        if(dot(u,v)<0)v=v*(-1.0)
        val tangent=(u+v)*(1/length(u+v))
        val lo=max(min(dot(x0,tangent),dot(x1,tangent)),min(dot(y0,tangent),dot(y1,tangent)))
        val hi=min(max(dot(x0,tangent),dot(x1,tangent)),max(dot(y0,tangent),dot(y1,tangent)))
        require(hi-lo>EPS) {"两条边没有可合并的共同区间"}
        fun at(start:Point,end:Point,s:Double):Point {
            val d=end-start;val k=dot(d,tangent);require(abs(k)>EPS) {"两条边方向无法配对"}
            return start+d*((s-dot(start,tangent))/k)
        }
        val xLo=at(x0,x1,lo);val xHi=at(x0,x1,hi);val yLo=at(y0,y1,lo);val yHi=at(y0,y1,hi)
        require(length(xLo-yLo)>EPS && length(xHi-yHi)>EPS && max(length(xLo-yLo),length(xHi-yHi))<=512) {"间隙宽度须大于0且不超过512文档像素"}
        val bridgePoints=listOf(xLo,xHi,yHi,yLo)
        require(abs(area(bridgePoints))>EPS) {"间隙方向或面积无效"}
        val turns=bridgePoints.indices.map {i->cross(bridgePoints[(i+1)%4]-bridgePoints[i],bridgePoints[(i+2)%4]-bridgePoints[(i+1)%4])}
        require(turns.all {it>EPS} || turns.all {it< -EPS}) {"相对边交叉，不能构造有效间隙"}
        val bridge=polygonPath(bridgePoints)
        require(contains(bridge,(first.second.point+second.second.point)*0.5)) {"合并线未经过两条边的共同投影区间"}
        // A bridge joins only empty space. It must not fill unrelated artwork or fold into a panel.
        for(panel in panels)require(!nonempty(boolean(bridge,panel.outline,Path.Op.INTERSECT))) {"合并桥与已有分格重叠，请调整手势"}
        val seen=panels.map {it.shape.getString("id")}.toSet()
        for(other in ArtShapes.items(layer)) {
            if(other.getString("id") in seen || !other.getBoolean("visible") || other.getDouble("opacity")==0.0)continue
            val outline=ArtShapes.path(other).apply {transform(transform(state,layer,other))}
            require(!nonempty(boolean(bridge,outline,Path.Op.INTERSECT))) {"待合并间隙与其他轮廓重叠"}
        }
        val joined=boolean(boolean(x.outline,bridge,Path.Op.UNION),y.outline,Path.Op.UNION)
        val all=ArtShapes.items(layer);val reference=if(all.indexOf(x.shape)<=all.indexOf(y.shape))x else y
        val removed=listOf(x.shape.getString("id"),y.shape.getString("id")).distinct()
        return replacement(layerId,removed,listOf(reference.shape.getString("id") to listOf(resultShape(reference,joined))))
            .put("approximationPx",PATH_TOLERANCE.toDouble())
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
