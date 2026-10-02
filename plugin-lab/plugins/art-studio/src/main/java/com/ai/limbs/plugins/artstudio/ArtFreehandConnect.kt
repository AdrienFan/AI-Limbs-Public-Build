package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject

/** Explicit endpoint references are resolved inside the same revision-checked history operation. */
internal object ArtFreehandConnect {
    data class Endpoint(val id:String,val part:Int,val node:Int)
    fun endpoint(p:JSONObject)=Endpoint(p.getString("id"),p.getInt("subpath"),p.getInt("node"))
    fun candidates(state:JSONObject,layerId:String):List<Pair<Endpoint,ArtPathGeometry.Vec>> {
        val layer=ArtShapes.layer(state,layerId)
        return ArtShapes.items(layer).asReversed().filter {it.getString("kind")=="path"&&it.getBoolean("visible")&&!it.getBoolean("locked")&&it.getDouble("opacity")>0}.flatMap {shape->
            val m=ArtShapes.matrix(shape.getJSONArray("matrix"))
            ArtPathTopology.parts(shape).flatMapIndexed {i,part->
                if(part.closed)emptyList() else listOf(0,part.nodes.lastIndex).map {j->
                    val p=part.nodes[j].point;val xy=floatArrayOf(p.x.toFloat(),p.y.toFloat());m.mapPoints(xy)
                    Endpoint(shape.getString("id"),i,j) to ArtPathGeometry.Vec(xy[0].toDouble(),xy[1].toDouble())
                }
            }
        }
    }
    fun json(e:Endpoint)=JSONObject().put("id",e.id).put("subpath",e.part).put("node",e.node)
    fun apply(layer:JSONObject,p:JSONObject):String {
        val all=ArtShapes.items(layer);val incoming=ArtShapes.normalize(p.getJSONObject("shape"))
        val start=if(p.has("startEndpoint"))endpoint(p.getJSONObject("startEndpoint")) else null
        val end=if(p.has("endEndpoint"))endpoint(p.getJSONObject("endEndpoint")) else null
        if(start==null&&end==null) {require(all.size<512);layer.getJSONArray("shapes").put(incoming);return incoming.getString("id")}
        require(!incoming.getBoolean("closed")) {"接续轨迹本身必须开放；接回原路径另一端时自动闭合"}
        val endpoints=listOfNotNull(start,end);require(endpoints.distinct().size==endpoints.size) {"不能将轨迹两端接到同一个已有端点"}
        val ids=endpoints.map {it.id}.distinct();val target=all.first {it.getString("id")==ids.first()}
        val inverse=Matrix();require(ArtShapes.matrix(target.getJSONArray("matrix")).invert(inverse))
        val parts=mutableListOf<ArtPathTopology.Part>();val references=mutableMapOf<Endpoint,ArtPathTopology.Ref>()
        fun map(v:ArtPathGeometry.Vec,m:Matrix):ArtPathGeometry.Vec {val xy=floatArrayOf(v.x.toFloat(),v.y.toFloat());m.mapPoints(xy);return ArtPathGeometry.Vec(xy[0].toDouble(),xy[1].toDouble())}
        for(id in ids) {
            val shape=all.first {it.getString("id")==id};require(shape.getString("kind")=="path"&&!shape.getBoolean("locked")&&shape.getBoolean("visible"))
            val decoded=ArtPathTopology.parts(shape);val transform=ArtShapes.matrix(shape.getJSONArray("matrix")).apply {postConcat(inverse)}
            for(e in endpoints.filter {it.id==id}) {require(e.part in decoded.indices);val part=decoded[e.part];require(!part.closed&&(e.node==0||e.node==part.nodes.lastIndex));references[e]=ArtPathTopology.Ref(parts.size+e.part,e.node)}
            decoded.forEach {part->parts.add(ArtPathTopology.Part(part.nodes.map {n->if(id==ids.first())n.copy() else ArtPathGeometry.Node(map(n.point,transform),n.incoming?.let {map(it,transform)},n.outgoing?.let {map(it,transform)},n.type)}.toMutableList(),part.closed))}
        }
        val fresh=ArtPathTopology.parts(incoming).single().nodes.map {n->ArtPathGeometry.Node(map(n.point,inverse),n.incoming?.let {map(it,inverse)},n.outgoing?.let {map(it,inverse)},n.type)}.toMutableList()
        fun reversed(nodes:List<ArtPathGeometry.Node>)=nodes.asReversed().map {ArtPathGeometry.Node(it.point,it.outgoing,it.incoming,it.type)}.toMutableList()
        fun snap(n:ArtPathGeometry.Node,v:ArtPathGeometry.Vec) {val d=v-n.point;n.point=v;n.incoming=n.incoming?.plus(d);n.outgoing=n.outgoing?.plus(d)}
        val consumed=mutableSetOf<Int>();var joined=fresh;var closed=false
        val first=start?.let {references.getValue(it)};val last=end?.let {references.getValue(it)}
        if(first!=null) {
            val old=if(first.node==0)reversed(parts[first.part].nodes) else parts[first.part].nodes.map {it.copy()}.toMutableList()
            snap(joined.first(),old.last().point)
            val junction=ArtPathGeometry.Node(old.last().point,old.last().incoming,joined.first().outgoing,"corner")
            joined=(old.dropLast(1)+listOf(junction)+joined.drop(1)).toMutableList();consumed.add(first.part)
        }
        if(last!=null) {
            if(first!=null&&last.part==first.part) {
                require(last.node!=first.node);snap(joined.last(),joined.first().point)
                joined.first().incoming=joined.last().incoming;joined.first().type="corner";joined.removeAt(joined.lastIndex);closed=true
            } else {
                val old=if(last.node!=0)reversed(parts[last.part].nodes) else parts[last.part].nodes.map {it.copy()}.toMutableList()
                snap(joined.last(),old.first().point)
                val junction=ArtPathGeometry.Node(old.first().point,joined.last().incoming,old.first().outgoing,"corner")
                joined=(joined.dropLast(1)+listOf(junction)+old.drop(1)).toMutableList();consumed.add(last.part)
            }
        }
        val result=parts.filterIndexed {i,_->i !in consumed}.toMutableList();result.add(consumed.min(),ArtPathTopology.Part(joined,closed))
        val next=ArtPathTopology.write(target,result)
        layer.put("shapes",JSONArray(all.filter {it.getString("id") !in ids||it.getString("id")==target.getString("id")}.map {if(it.getString("id")==target.getString("id"))next else it}))
        return target.getString("id")
    }
}
