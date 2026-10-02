package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** M/Z extend the existing L/C command stream; no parallel geometry is stored in the document. */
internal object ArtPathTopology {
    data class Part(val nodes:MutableList<ArtPathGeometry.Node>,var closed:Boolean)
    data class Ref(val part:Int,val node:Int)
    fun copy(n:ArtPathGeometry.Node)=n.copy()
    fun parts(shape:JSONObject):MutableList<Part> {
        require(shape.getString("kind")=="path")
        val points=shape.getJSONArray("points");val commands=shape.getJSONArray("commands");var cursor=0
        fun point():ArtPathGeometry.Vec {val p=points.getJSONArray(cursor++);require(p.length()==2);return ArtPathGeometry.Vec(p.getDouble(0),p.getDouble(1))}
        val result=mutableListOf<Part>();var current=Part(mutableListOf(ArtPathGeometry.Node(point())),false)
        fun finish() {
            if(current.closed&&current.nodes.size>1&&(current.nodes.first().point-current.nodes.last().point).length()<=0.000001) {
                current.nodes.first().incoming=current.nodes.last().incoming;current.nodes.removeAt(current.nodes.lastIndex)
            }
            require(current.nodes.size in 1..2049 && (current.closed||current.nodes.size>=2)) {"子路径至少两个节点；闭合曲线可为单节点"}
            result.add(current)
        }
        for(i in 0 until commands.length())when(commands.getString(i)) {
            "M"->{finish();current=Part(mutableListOf(ArtPathGeometry.Node(point())),false)}
            "Z"->{require(!current.closed);current.closed=true;require(i==commands.length()-1 || commands.getString(i+1)=="M") {"Z后须开始新子路径"}}
            "L"->{require(!current.closed);current.nodes.add(ArtPathGeometry.Node(point()))}
            "C"->{require(!current.closed);current.nodes.last().outgoing=point();val inside=point();current.nodes.add(ArtPathGeometry.Node(point(),incoming=inside))}
            else->error("路径命令仅支持M/L/C/Z")
        }
        if(shape.getBoolean("closed")) {require(result.isEmpty()&&!current.closed);current.closed=true}
        finish();require(cursor==points.length()&&result.size<=64)
        // Count stored drawing commands, retaining the existing limit for legacy implicit closing edges.
        require((0 until commands.length()).count {commands.getString(it) in setOf("L","C")} in 1..ArtFreehand.MAX_SEGMENTS)
        val flat=result.flatMap {it.nodes}
        if(shape.has("nodeModes")) {val modes=shape.getJSONArray("nodeModes");require(modes.length()==flat.size);flat.forEachIndexed {i,n->n.type=modes.getString(i);require(n.type in ArtPathGeometry.types)}}
        return result
    }
    fun ref(parts:List<Part>,index:Int):Ref {
        require(index>=0);var offset=0
        parts.forEachIndexed {i,p->if(index<offset+p.nodes.size)return Ref(i,index-offset);offset+=p.nodes.size}
        error("路径节点已不存在")
    }
    fun flatIndex(parts:List<Part>,r:Ref)=parts.take(r.part).sumOf {it.nodes.size}+r.node
    fun geometry(parts:List<Part>):JSONObject {
        require(parts.isNotEmpty()&&parts.size<=64)
        if(parts.size==1)return ArtPathGeometry.geometry(parts[0].nodes,parts[0].closed)
        val points=JSONArray();val commands=JSONArray();val modes=JSONArray()
        parts.forEachIndexed {i,p->
            val g=ArtPathGeometry.geometry(p.nodes,p.closed);val a=g.getJSONArray("points");val c=g.getJSONArray("commands")
            if(i>0)commands.put("M")
            for(j in 0 until a.length())points.put(a.getJSONArray(j))
            for(j in 0 until c.length())commands.put(c.getString(j))
            if(p.closed)commands.put("Z")
            p.nodes.forEach {modes.put(it.type)}
        }
        require(points.length()<=ArtFreehand.MAX_GEOMETRY_POINTS)
        return JSONObject().put("points",points).put("commands",commands).put("closed",false).put("nodeModes",modes)
    }
    fun write(shape:JSONObject,parts:List<Part>):JSONObject {
        val out=JSONObject(shape.toString());val g=geometry(parts);g.keys().forEach {out.put(it,g.get(it))};out.put("geometryEdited",true);return ArtShapes.normalize(out)
    }
    fun path(shape:JSONObject,fillOnly:Boolean=false)=Path().apply {
        parts(shape).filter { !fillOnly||it.closed }.forEach {addPath(ArtPathGeometry.preview(it.nodes,it.closed))}
        fillType=if(ArtObjectStyle.settings(shape).getString("fillRule")=="evenodd")Path.FillType.EVEN_ODD else Path.FillType.WINDING
    }
    fun edited(shape:JSONObject,edits:JSONArray):JSONObject {
        require(edits.length() in 1..64);var result=JSONObject(shape.toString())
        for(i in 0 until edits.length()) {
            val parts=parts(result);val e=JSONObject(edits.getJSONObject(i).toString())
            if(e.getString("action") in setOf("move_nodes","node_types","delete_nodes")) {
                val selected=e.getJSONArray("nodes");val indices=(0 until selected.length()).map {selected.getInt(it)}
                require(indices.isNotEmpty()&&indices.distinct().size==indices.size)
                val refs=indices.map {ref(parts,it)}
                if(e.getString("action")=="delete_nodes") {
                    parts.forEachIndexed {j,part->require(part.nodes.size-refs.count {it.part==j}>=(if(part.closed)1 else 2)) {"删除后子路径节点不足"}}
                    refs.sortedWith(compareByDescending<Ref> {it.part}.thenByDescending {it.node}).forEach {r->parts[r.part].nodes.removeAt(r.node)}
                    parts.filter {!it.closed}.forEach {it.nodes.first().incoming=null;it.nodes.last().outgoing=null}
                } else refs.forEach {r->
                    val n=parts[r.part].nodes[r.node]
                    val action=if(e.getString("action")=="move_nodes")JSONObject().put("action","move_node").put("node",r.node).put("x",n.point.x+e.getDouble("dx")).put("y",n.point.y+e.getDouble("dy"))
                        else JSONObject().put("action","node_type").put("node",r.node).put("type",e.getString("type"))
                    ArtPathGeometry.apply(parts[r.part].nodes,parts[r.part].closed,action)
                }
                result=write(result,parts);continue
            }
            val key=if(e.has("segment"))"segment" else "node"
            val r=if(e.has("subpath"))Ref(e.getInt("subpath"),if(e.has(key))e.getInt(key) else 0) else if(e.has(key))ref(parts,e.getInt(key)) else Ref(0,0)
            require(r.part in parts.indices);val p=parts[r.part];if(e.has(key))e.put(key,r.node)
            p.closed=ArtPathGeometry.apply(p.nodes,p.closed,e);result=write(result,parts)
        };return result
    }
    fun topology(shape:JSONObject,p:JSONObject):JSONObject {
        val parts=parts(shape);val action=p.getString("action")
        fun reference(key:String):Ref {
            val a=p.getJSONArray(key);require(a.length()==2);val r=Ref(a.getInt(0),a.getInt(1));require(r.part in parts.indices&&r.node in parts[r.part].nodes.indices);return r
        }
        if(action in setOf("break_node","break_segment")) {
            val r=reference("at");val original=parts[r.part];val nodes=original.nodes
            if(action=="break_node") {
                if(original.closed) {
                    val ordered=(nodes.drop(r.node)+nodes.take(r.node)+listOf(copy(nodes[r.node]))).map {copy(it)}.toMutableList()
                    ordered.first().incoming=null;ordered.last().outgoing=null;ordered.first().type="corner";ordered.last().type="corner"
                    parts[r.part]=Part(ordered,false)
                } else {
                    require(r.node in 1 until nodes.lastIndex) {"只可在开放路径的内部节点断开"}
                    val left=nodes.take(r.node+1).map {copy(it)}.toMutableList();val right=nodes.drop(r.node).map {copy(it)}.toMutableList()
                    left.last().outgoing=null;right.first().incoming=null;left.last().type="corner";right.first().type="corner"
                    parts[r.part]=Part(left,false);parts.add(r.part+1,Part(right,false))
                }
            } else {
                require(r.node<ArtPathGeometry.segmentCount(nodes,original.closed))
                if(original.closed) {
                    val ordered=(nodes.drop(r.node+1)+nodes.take(r.node+1)).map {copy(it)}.toMutableList();require(ordered.size>=2)
                    ordered.first().incoming=null;ordered.last().outgoing=null;parts[r.part]=Part(ordered,false)
                } else {
                    require(r.node in 1 until nodes.size-2) {"断段后两侧都须至少两个节点"}
                    val left=nodes.take(r.node+1).map {copy(it)}.toMutableList();val right=nodes.drop(r.node+1).map {copy(it)}.toMutableList()
                    left.last().outgoing=null;right.first().incoming=null;parts[r.part]=Part(left,false);parts.add(r.part+1,Part(right,false))
                }
            }
        } else {
            require(action in setOf("join","merge"));val a=reference("first");val b=reference("second");require(a!=b)
            fun endpoint(r:Ref) {val n=parts[r.part];require(!n.closed&&(r.node==0||r.node==n.nodes.lastIndex)) {"只能连接或合并开放子路径的端点"}}
            endpoint(a);endpoint(b)
            fun reverse(nodes:List<ArtPathGeometry.Node>)=nodes.asReversed().map {ArtPathGeometry.Node(it.point,it.outgoing,it.incoming,it.type)}.toMutableList()
            if(a.part==b.part) {
                val nodes=parts[a.part].nodes
                if(action=="join") {
                    nodes.last().outgoing=nodes.last().incoming?.let {nodes.last().point*2.0-it}
                    nodes.first().incoming=nodes.first().outgoing?.let {nodes.first().point*2.0-it}
                    parts[a.part].closed=true
                } else {
                    val start=nodes.first();val end=nodes.last();val middle=(start.point+end.point)*0.5
                    val merged=ArtPathGeometry.Node(middle,end.incoming?.let {middle+(it-end.point)},start.outgoing?.let {middle+(it-start.point)},"corner")
                    parts[a.part]=Part((listOf(merged)+nodes.drop(1).dropLast(1)).toMutableList(),true)
                }
            } else {
                val left=if(a.node==0)reverse(parts[a.part].nodes) else parts[a.part].nodes.map {copy(it)}.toMutableList()
                val right=if(b.node!=0)reverse(parts[b.part].nodes) else parts[b.part].nodes.map {copy(it)}.toMutableList()
                val joined=if(action=="join") {
                    left.last().outgoing=left.last().incoming?.let {left.last().point*2.0-it}
                    right.first().incoming=right.first().outgoing?.let {right.first().point*2.0-it};(left+right).toMutableList()
                } else {
                    val x=left.last();val y=right.first();val middle=(x.point+y.point)*0.5
                    val merged=ArtPathGeometry.Node(middle,x.incoming?.let {middle+(it-x.point)},y.outgoing?.let {middle+(it-y.point)},"corner")
                    (left.dropLast(1)+listOf(merged)+right.drop(1)).toMutableList()
                }
                parts[min(a.part,b.part)]=Part(joined,false);parts.removeAt(max(a.part,b.part))
            }
        }
        return write(shape,parts)
    }
    fun converted(shape:JSONObject):JSONObject {
        if(shape.getString("kind")=="path")return JSONObject(shape.toString())
        val a=shape.getJSONArray("points");val p0=a.getJSONArray(0);val p1=a.getJSONArray(1)
        val l=min(p0.getDouble(0),p1.getDouble(0));val t=min(p0.getDouble(1),p1.getDouble(1));val r=max(p0.getDouble(0),p1.getDouble(0));val b=max(p0.getDouble(1),p1.getDouble(1))
        fun node(x:Double,y:Double)=ArtPathGeometry.Node(ArtPathGeometry.Vec(x,y))
        val nodes=mutableListOf<ArtPathGeometry.Node>();var closed=true
        when(shape.getString("kind")) {
            "line"->{nodes.add(node(p0.getDouble(0),p0.getDouble(1)));nodes.add(node(p1.getDouble(0),p1.getDouble(1)));closed=false}
            "polygon"->for(i in 0 until a.length()) {val v=a.getJSONArray(i);nodes.add(node(v.getDouble(0),v.getDouble(1)))}
            "ellipse"->{val x=(l+r)/2;val y=(t+b)/2;val rx=(r-l)/2;val ry=(b-t)/2;val k=0.5522847498307936
                nodes.addAll(listOf(node(x,t),node(r,y),node(x,b),node(l,y)))
                nodes[0].incoming=ArtPathGeometry.Vec(x-k*rx,t);nodes[0].outgoing=ArtPathGeometry.Vec(x+k*rx,t)
                nodes[1].incoming=ArtPathGeometry.Vec(r,y-k*ry);nodes[1].outgoing=ArtPathGeometry.Vec(r,y+k*ry)
                nodes[2].incoming=ArtPathGeometry.Vec(x+k*rx,b);nodes[2].outgoing=ArtPathGeometry.Vec(x-k*rx,b)
                nodes[3].incoming=ArtPathGeometry.Vec(l,y+k*ry);nodes[3].outgoing=ArtPathGeometry.Vec(l,y-k*ry);nodes.forEach {it.type="smooth"}}
            "rectangle"->{val radius=min(shape.optDouble("cornerRadius",0.0),min(r-l,b-t)/2)
                if(radius==0.0)nodes.addAll(listOf(node(l,t),node(r,t),node(r,b),node(l,b)))
                else {
                    nodes.addAll(listOf(node(l+radius,t),node(r-radius,t),node(r,t+radius),node(r,b-radius),node(r-radius,b),node(l+radius,b),node(l,b-radius),node(l,t+radius)))
                    val k=radius*0.5522847498307936
                    nodes[1].outgoing=ArtPathGeometry.Vec(r-radius+k,t);nodes[2].incoming=ArtPathGeometry.Vec(r,t+radius-k)
                    nodes[3].outgoing=ArtPathGeometry.Vec(r,b-radius+k);nodes[4].incoming=ArtPathGeometry.Vec(r-radius+k,b)
                    nodes[5].outgoing=ArtPathGeometry.Vec(l+radius-k,b);nodes[6].incoming=ArtPathGeometry.Vec(l,b-radius+k)
                    nodes[7].outgoing=ArtPathGeometry.Vec(l,t+radius-k);nodes[0].incoming=ArtPathGeometry.Vec(l+radius-k,t)
                }}
            else->error("此对象不能转换为路径")
        }
        val out=JSONObject(shape.toString()).put("kind","path");out.remove("cornerRadius");return write(out,listOf(Part(nodes,closed)))
    }
    fun info()=JSONObject().put("format","Existing points/commands extended by M (new start) and Z (close); closed remains the single-path flag, subpaths returned by path.nodes")
        .put("actions","break_node duplicates an interior node and preserves both incident curves; break_segment removes its outgoing segment; join adds a segment, merge averages endpoint positions and preserves incident handle offsets")
        .put("references","topology endpoints are [subpath,node], zero-based from path.nodes.subpaths; path.edit node is flattened global index unless subpath is explicit")
        .put("multi","move_nodes {nodes:[global indices],dx,dy}; node_types {nodes,type}; delete_nodes {nodes}; 64 edit actions, up to 2049 nodes per subpath, 64 subpaths, 2048 segments total")
        .put("conversion","line/polygon use exact segments; ellipse/rounded rectangle use cubic quarter arcs (kappa approximation); id, matrix and object style preserved")
        .put("combine","ids order selects destination/style; other object matrices mapped into its local space; removes consumed objects in one undoable operation; use topology next to join endpoints")
    fun describe(shape:JSONObject):JSONArray=JSONArray(parts(shape).mapIndexed {i,p->JSONObject().put("subpath",i).put("closed",p.closed).put("nodes",ArtPathGeometry.json(p.nodes))})
}
