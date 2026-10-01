package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot

/** Logical nodes are a view of the existing L/C format, not a second document representation. */
internal object ArtPathGeometry {
    val types = setOf("corner", "smooth", "symmetric")
    data class Vec(val x:Double, val y:Double) {
        operator fun plus(v:Vec)=Vec(x+v.x,y+v.y)
        operator fun minus(v:Vec)=Vec(x-v.x,y-v.y)
        operator fun times(n:Double)=Vec(x*n,y*n)
        fun length()=hypot(x,y)
        fun json()=JSONArray().put(x).put(y)
    }
    data class Node(var point:Vec,var incoming:Vec?=null,var outgoing:Vec?=null,var type:String="corner")
    private fun point(a:JSONArray):Vec {
        require(a.length()==2)
        val p=Vec(a.getDouble(0),a.getDouble(1));validate(p);return p
    }
    private fun validate(p:Vec) {
        require(p.x.isFinite()&&p.y.isFinite()&&kotlin.math.abs(p.x)<=1000000.0&&
            kotlin.math.abs(p.y)<=1000000.0) { "路径节点或控制柄超出可编辑范围" }
    }
    fun parse(a:JSONArray):MutableList<Node> {
        require(a.length() in 1..2049) { "路径节点数需要在1至2049之间" }
        return (0 until a.length()).map { i ->
            val n=a.getJSONObject(i)
            Node(Vec(n.getDouble("x"),n.getDouble("y")),
                if(n.has("in")&&!n.isNull("in")) point(n.getJSONArray("in")) else null,
                if(n.has("out")&&!n.isNull("out")) point(n.getJSONArray("out")) else null,
                n.optString("type","corner")).also {
                validate(it.point);require(it.type in types)
            }
        }.toMutableList()
    }
    fun json(nodes:List<Node>):JSONArray=JSONArray(nodes.map { n -> JSONObject()
        .put("x",n.point.x).put("y",n.point.y).put("in",n.incoming?.json()?:JSONObject.NULL)
        .put("out",n.outgoing?.json()?:JSONObject.NULL).put("type",n.type) })
    fun nodes(shape:JSONObject):MutableList<Node> {
        require(shape.getString("kind")=="path") { "此对象不是可编辑路径" }
        val a=shape.getJSONArray("points");val commands=shape.getJSONArray("commands")
        val nodes=mutableListOf(Node(point(a.getJSONArray(0))))
        var cursor=1
        for(i in 0 until commands.length()) {
            when(commands.getString(i)) {
                "L" -> nodes.add(Node(point(a.getJSONArray(cursor++))))
                "C" -> {
                    nodes.last().outgoing=point(a.getJSONArray(cursor++))
                    val incoming=point(a.getJSONArray(cursor++))
                    nodes.add(Node(point(a.getJSONArray(cursor++)),incoming=incoming))
                }
                else -> error("不支持的路径命令")
            }
        }
        require(cursor==a.length())
        if(shape.getBoolean("closed")&&nodes.size>1&&
            (nodes.first().point-nodes.last().point).length()<=0.000001) {
            nodes.first().incoming=nodes.last().incoming;nodes.removeAt(nodes.lastIndex)
        }
        shape.optJSONArray("nodeModes")?.let { modes ->
            require(modes.length()==nodes.size) { "节点类型数量不匹配" }
            nodes.forEachIndexed { i,n -> n.type=modes.getString(i);require(n.type in types) }
        }
        return nodes
    }
    fun validateModes(shape:JSONObject) { if(shape.has("nodeModes")) nodes(shape) }
    fun segmentCount(nodes:List<Node>,closed:Boolean)=if(closed) nodes.size else nodes.size-1
    fun supports(nodes:List<Node>,closed:Boolean,index:Int,side:String):Boolean =
        closed || if(side=="in") index>0 else index<nodes.lastIndex

    fun geometry(nodes:List<Node>,closed:Boolean):JSONObject {
        require(nodes.size in 1..2049 && (closed||nodes.size>=2))
        val count=segmentCount(nodes,closed);require(count in 1..ArtFreehand.MAX_SEGMENTS)
        val points=JSONArray().put(nodes.first().point.json());val commands=JSONArray()
        for(i in 0 until count) {
            val start=nodes[i];val end=nodes[(i+1)%nodes.size]
            if(start.outgoing==null&&end.incoming==null) {
                commands.put("L");points.put(end.point.json())
            } else {
                commands.put("C")
                // A missing control is the endpoint itself: an explicit zero-length handle.
                points.put((start.outgoing?:start.point).json()).put((end.incoming?:end.point).json())
                    .put(end.point.json())
            }
        }
        return JSONObject().put("points",points).put("commands",commands).put("closed",closed)
            .put("nodeModes",JSONArray(nodes.map { it.type }))
    }
    fun preview(nodes:List<Node>,closed:Boolean):Path=Path().apply {
        if(nodes.isNotEmpty()) {
            moveTo(nodes.first().point.x.toFloat(),nodes.first().point.y.toFloat())
            for(i in 0 until segmentCount(nodes,closed)) {
                val start=nodes[i];val end=nodes[(i+1)%nodes.size]
                val out=start.outgoing;val inside=end.incoming
                if(out==null&&inside==null) lineTo(end.point.x.toFloat(),end.point.y.toFloat())
                else {
                    val a=out?:start.point;val b=inside?:end.point
                    cubicTo(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),
                        end.point.x.toFloat(),end.point.y.toFloat())
                }
            }
            if(closed) close()
        }
    }
    fun create(p:JSONObject):JSONObject {
        val nodes=parse(p.getJSONArray("nodes"));val closed=p.optBoolean("closed",false)
        if(!closed) { nodes.first().incoming=null;nodes.last().outgoing=null }
        for(i in nodes.indices) setType(nodes,closed,i,nodes[i].type)
        val shape=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","path")
        val g=geometry(nodes,closed);g.keys().forEach { shape.put(it,g.get(it)) }
        p.optJSONObject("style")?.let { style ->
            require(style.keys().asSequence().all { it in setOf("fill","stroke","strokeWidth","opacity") })
            style.keys().forEach { shape.put(it,style.get(it)) }
        }
        return ArtShapes.normalize(shape)
    }
    fun moveHandle(nodes:MutableList<Node>,closed:Boolean,index:Int,side:String,p:Vec) {
        require(side in setOf("in","out")&&supports(nodes,closed,index,side));validate(p)
        val n=nodes[index];val other=if(side=="in") n.outgoing else n.incoming
        if(side=="in") n.incoming=p else n.outgoing=p
        val opposite=if(side=="in") "out" else "in"
        if(n.type!="corner"&&supports(nodes,closed,index,opposite)) {
            val direction=n.point-p;val size=direction.length()
            val length=if(n.type=="symmetric") size else other?.let { (it-n.point).length() }?:size
            // A zero dragged handle has no direction; keep the other handle for smooth nodes.
            val linked=if(size>0.000001) n.point+direction*(length/size)
                else if(n.type=="symmetric") n.point else other
            if(opposite=="in") n.incoming=linked else n.outgoing=linked
        }
    }
    private fun setType(nodes:MutableList<Node>,closed:Boolean,index:Int,type:String) {
        require(type in types)
        val n=nodes[index];n.type=type
        if(type=="corner") return
        // Type conversion adds usable handles where an earlier segment had zero-length controls.
        if(n.incoming?.let { (it-n.point).length()<=0.000001 }==true) n.incoming=null
        if(n.outgoing?.let { (it-n.point).length()<=0.000001 }==true) n.outgoing=null
        val out=n.outgoing;val inside=n.incoming
        when {
            out!=null&&(out-n.point).length()>0.000001 -> moveHandle(nodes,closed,index,"out",out)
            inside!=null&&(inside-n.point).length()>0.000001 -> moveHandle(nodes,closed,index,"in",inside)
            supports(nodes,closed,index,"out") -> {
                val next=nodes[(index+1)%nodes.size].point
                moveHandle(nodes,closed,index,"out",n.point+(next-n.point)*(1.0/3.0))
            }
            else -> {
                val previous=nodes[index-1].point
                moveHandle(nodes,closed,index,"in",n.point+(previous-n.point)*(1.0/3.0))
            }
        }
    }
    fun apply(nodes:MutableList<Node>,closed:Boolean,e:JSONObject):Boolean {
        var result=closed
        val action=e.getString("action")
        if(action=="closed") {
            result=e.getBoolean("value")
            if(!result) { require(nodes.size>=2);nodes.first().incoming=null;nodes.last().outgoing=null }
            else {
                setType(nodes,true,0,nodes.first().type)
                if(nodes.size>1) setType(nodes,true,nodes.lastIndex,nodes.last().type)
            }
            return result
        }
        if(action in setOf("insert_node","segment_type")) {
            val i=e.getInt("segment");require(i in 0 until segmentCount(nodes,closed))
            val a=nodes[i];val b=nodes[(i+1)%nodes.size]
            if(action=="segment_type") {
                when(e.getString("type")) {
                    "line" -> { a.outgoing=null;b.incoming=null }
                    "curve" -> {
                        if(a.outgoing==null) a.outgoing=a.point+(b.point-a.point)*(1.0/3.0)
                        if(b.incoming==null) b.incoming=b.point+(a.point-b.point)*(1.0/3.0)
                    }
                    else -> error("线段类型需要line或curve")
                }
                a.type="corner";b.type="corner"
            } else {
                require(nodes.size<2049 && (!closed||nodes.size<2048))
                val t=e.optDouble("t",0.5);require(t.isFinite()&&t in 0.01..0.99)
                fun mix(a:Vec,b:Vec)=a*(1.0-t)+b*t
                val inserted:Node
                if(a.outgoing==null&&b.incoming==null) inserted=Node(mix(a.point,b.point))
                else {
                    val q0=mix(a.point,a.outgoing?:a.point)
                    val q1=mix(a.outgoing?:a.point,b.incoming?:b.point)
                    val q2=mix(b.incoming?:b.point,b.point)
                    val r0=mix(q0,q1);val r1=mix(q1,q2)
                    inserted=Node(mix(r0,r1),r0,r1,"smooth")
                    a.outgoing=q0;b.incoming=q2
                    // Subdivision preserves the curve but changes adjacent handle lengths.
                    if(a.type=="symmetric") a.type="smooth"
                    if(b.type=="symmetric") b.type="smooth"
                }
                nodes.add(i+1,inserted)
            }
            return result
        }
        val i=e.getInt("node");require(i in nodes.indices) { "路径节点已不存在" }
        val n=nodes[i]
        when(action) {
            "move_node" -> {
                val p=Vec(e.getDouble("x"),e.getDouble("y"));validate(p);val offset=p-n.point
                n.point=p;n.incoming=n.incoming?.plus(offset);n.outgoing=n.outgoing?.plus(offset)
            }
            "move_handle" -> moveHandle(nodes,closed,i,e.getString("side"),Vec(e.getDouble("x"),e.getDouble("y")))
            "node_type" -> setType(nodes,closed,i,e.getString("type"))
            "delete_node" -> {
                require(nodes.size>(if(closed) 1 else 2)) { "删除后路径节点不足，请删除整个对象" }
                nodes.removeAt(i)
                if(!closed) { nodes.first().incoming=null;nodes.last().outgoing=null }
            }
            else -> error("尚未实现此路径编辑动作")
        }
        return result
    }
    fun edited(shape:JSONObject,edits:JSONArray):JSONObject {
        require(edits.length() in 1..64) { "每次路径编辑需要1至64个动作" }
        val nodes=nodes(shape);var closed=shape.getBoolean("closed")
        for(i in 0 until edits.length()) closed=apply(nodes,closed,edits.getJSONObject(i))
        val result=JSONObject(shape.toString());val g=geometry(nodes,closed)
        g.keys().forEach { result.put(it,g.get(it)) }
        result.put("geometryEdited",true)
        return ArtShapes.normalize(result)
    }
}
