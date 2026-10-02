package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** Exact cubic contours and a bounded constructive selection history, independent of artwork layers. */
internal object ArtBezierSelection {
    const val MAX_NODES=2048
    const val MAX_PARTS=32
    const val MAX_TOTAL_NODES=8192
    val modes=ArtSoftSelection.modes
    val pending=listOf("角度吸附","任意旋转选区","独立选区蒙版图层")
    fun info()=JSONObject().put("modes",JSONObject(modes)).put("maxNodes",MAX_NODES)
        .put("maxParts",MAX_PARTS).put("maxTotalNodes",MAX_TOTAL_NODES)
        .put("defaults",ArtSoftSelection.defaults()).put("softSelection",ArtSoftSelection.info())
        .put("coordinateSpace","document").put("closed",true).put("pending",JSONArray(pending))
        .put("scope","真实闭合三次曲线，复合选区保留各操作的曲线节点；选区移动和缩放不折线化。仅改变选区，不写作品像素。")
    fun empty()=JSONObject().put("shape","rect").put("x",0).put("y",0).put("width",0).put("height",0)
    fun frame(s: JSONObject)=RectF(s.getDouble("x").toFloat(),s.getDouble("y").toFloat(),
        (s.getDouble("x")+s.getDouble("width")).toFloat(),(s.getDouble("y")+s.getDouble("height")).toFloat())
    private fun frameJson(b: RectF)=JSONObject().put("x",b.left).put("y",b.top).put("width",b.width()).put("height",b.height())
    private fun copy(s: JSONObject)=JSONObject(s.toString())
    fun fromNodes(raw: JSONArray): JSONObject {
        val nodes=ArtPathGeometry.parse(raw)
        require(nodes.size in 2..MAX_NODES) {"闭合曲线选区需要2–2048节点"}
        nodes.forEachIndexed {i,n->if(n.type!="corner")ArtPathGeometry.apply(nodes,true,
            JSONObject().put("action","node_type").put("node",i).put("type",n.type))}
        val outline=ArtPathGeometry.preview(nodes,true).apply {fillType=Path.FillType.EVEN_ODD}
        val canonical=Path();check(canonical.op(outline,outline,Path.Op.UNION)) {"曲线选区计算失败"}
        require(!canonical.isEmpty) {"曲线选区面积为零"}
        val b=RectF();outline.computeBounds(b,true)
        require(b.width()>0f && b.height()>0f) {"曲线选区面积为零"}
        fun normalize(v: ArtPathGeometry.Vec)=ArtPathGeometry.Vec((v.x-b.left)/b.width(),(v.y-b.top)/b.height())
        val normalized=nodes.map {n->ArtPathGeometry.Node(normalize(n.point),n.incoming?.let(::normalize),
            n.outgoing?.let(::normalize),n.type)}
        return frameJson(b).put("shape","bezier").put("nodes",ArtPathGeometry.json(normalized))
    }
    fun nodes(s: JSONObject): MutableList<ArtPathGeometry.Node> {
        require(s.getString("shape")=="bezier") {"此选区分量不是贝塞尔曲线"}
        val nodes=ArtPathGeometry.parse(s.getJSONArray("nodes"));val b=frame(s)
        fun map(v: ArtPathGeometry.Vec)=ArtPathGeometry.Vec(b.left+v.x*b.width(),b.top+v.y*b.height())
        nodes.forEach {n->n.point=map(n.point);n.incoming=n.incoming?.let(::map);n.outgoing=n.outgoing?.let(::map)}
        return nodes
    }
    /** All children are simple contours. Scaling a compound frame is pushed to their bounding frames. */
    fun parts(s: JSONObject): MutableList<JSONObject> {
        if(s.has("curveParts"))return ArtCurveSoftSelection.parts(s)
        if(s.optString("shape","rect")!="compound")
            return mutableListOf(JSONObject().put("mode","replace").put("selection",copy(s)))
        val basis=frame(s.getJSONObject("basis"));val current=frame(s)
        val sx=current.width()/basis.width();val sy=current.height()/basis.height()
        return (0 until s.getJSONArray("parts").length()).map {i->
            val part=copy(s.getJSONArray("parts").getJSONObject(i));val child=part.getJSONObject("selection")
            child.put("x",current.left+(child.getDouble("x")-basis.left)*sx)
                .put("y",current.top+(child.getDouble("y")-basis.top)*sy)
                .put("width",child.getDouble("width")*sx).put("height",child.getDouble("height")*sy)
            part
        }.toMutableList()
    }
    fun operation(mode: String)=when(mode) {
        "add"->Path.Op.UNION;"subtract"->Path.Op.DIFFERENCE;"intersect"->Path.Op.INTERSECT;"xor"->Path.Op.XOR
        else->error("复合选区模式无效")
    }
    fun compoundPath(parts: List<JSONObject>): Path {
        require(parts.size in 1..MAX_PARTS)
        var result=ArtSelection.path(parts.first().getJSONObject("selection"))
        require(parts.first().getString("mode")=="replace")
        parts.drop(1).forEach {p->
            val next=Path();check(next.op(result,ArtSelection.path(p.getJSONObject("selection")),operation(p.getString("mode")))) {
                "复合选区计算失败"
            };result=next
        }
        return result
    }
    fun build(parts: List<JSONObject>): JSONObject {
        require(parts.size in 1..MAX_PARTS) {"复合选区最多32个分量，请先替换选区"}
        var count=0
        parts.forEach {p->
            val s=p.getJSONObject("selection");require(s.optString("shape","rect")!="compound")
            ArtSelection.validate(s)
            count+=when(s.optString("shape","rect")) {"bezier"->s.getJSONArray("nodes").length();"polygon"->s.getJSONArray("vertices").length();else->4}
        }
        require(parts.sumOf {it.getJSONObject("selection").let {child->if(child.optString("shape")=="raster")child.getInt("runCount") else 0}}<=ArtRasterSelection.MAX_RUNS) {"复合区域超过32768扫描段，请先替换选区"}
        require(count<=MAX_TOTAL_NODES) {"复合选区累计节点超过8192，请先替换选区"}
        val path=compoundPath(parts)
        if(path.isEmpty)return empty()
        val bounds=RectF();path.computeBounds(bounds,true)
        if(bounds.width()<=0f || bounds.height()<=0f)return empty()
        return frameJson(bounds).put("shape","compound").put("basis",frameJson(bounds))
            .put("parts",JSONArray(parts))
    }
    fun combine(current: JSONObject?,created: JSONObject,mode: String,width:Int,height:Int): JSONObject {
        if(current?.has("coverage")==true || created.has("coverage"))return ArtSoftSelection.combine(current,created,mode,width,height)
        require(mode in modes) {"选区模式无效"}
        if(mode=="replace")return created
        val noSelection=current==null || current.getDouble("width")==0.0 || current.getDouble("height")==0.0
        if(noSelection)return if(mode in setOf("add","xor"))created else empty()
        return build(parts(requireNotNull(current)).apply {
            add(JSONObject().put("mode",mode).put("selection",created))
        })
    }
    fun component(s: JSONObject,index: Int): JSONObject {
        val list=parts(s);require(index in list.indices) {"选区分量不存在"}
        return list[index].getJSONObject("selection")
    }
    fun edited(s: JSONObject,index: Int,edits: JSONArray): JSONObject {
        require(edits.length() in 1..64) {"每次选区编辑需要1–64动作"}
        val children=parts(s);require(index in children.indices)
        val curve=children[index].getJSONObject("selection");val nodes=nodes(curve)
        for(i in 0 until edits.length()) {
            val edit=edits.getJSONObject(i)
            require(edit.getString("action")!="closed") {"曲线选区必须保持闭合"}
            ArtPathGeometry.apply(nodes,true,edit)
            require(nodes.size in 2..MAX_NODES) {"选区至少保留两个节点"}
        }
        val updated=fromNodes(ArtPathGeometry.json(nodes))
        if(s.getString("shape")=="bezier")return updated
        children[index].put("selection",updated)
        return build(children)
    }
}
