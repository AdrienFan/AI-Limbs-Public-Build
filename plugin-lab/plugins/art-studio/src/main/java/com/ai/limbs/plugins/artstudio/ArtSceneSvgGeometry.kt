package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.util.UUID
import kotlin.math.*

/** SVG graphic primitives map back to the shared native geometry, never a parallel scene. */
internal object ArtSceneSvgGeometry {
    fun num(value:String):Double=value.trim().toDouble().also {require(it.isFinite()&&abs(it)<=1000000){"SVG 数字超出范围"}}
    fun number(e:Element,k:String,default:Double=0.0)=if(e.hasAttribute(k))num(e.getAttribute(k)) else default
    fun numbers(value:String):List<Double> {
        val tokens=Regex("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?").findAll(value).toList();var end=0
        val result=tokens.map {require(value.substring(end,it.range.first).all {c->c.isWhitespace()||c==','});end=it.range.last+1;num(it.value)}
        require(value.substring(end).all {it.isWhitespace()||it==','});return result
    }
    fun transform(value:String):Matrix {
        val result=Matrix();var end=0
        for(m in Regex("([A-Za-z]+)\\s*\\(([^)]*)\\)").findAll(value)) {
            require(value.substring(end,m.range.first).all {it.isWhitespace()||it==','});end=m.range.last+1
            val n=numbers(m.groupValues[2]);val op=Matrix()
            when(m.groupValues[1]) {
                "matrix"->{require(n.size==6);op.set(ArtShapes.matrix(JSONArray(n)))}
                "translate"->{require(n.size in 1..2);op.setTranslate(n[0].toFloat(),(if(n.size==2)n[1] else 0.0).toFloat())}
                "scale"->{require(n.size in 1..2);op.setScale(n[0].toFloat(),(if(n.size==2)n[1] else n[0]).toFloat())}
                "rotate"->{require(n.size==1||n.size==3);if(n.size==1)op.setRotate(n[0].toFloat())else op.setRotate(n[0].toFloat(),n[1].toFloat(),n[2].toFloat())}
                "skewX","skewY"->{require(n.size==1&&abs(n[0])<89.0);val t=tan(Math.toRadians(n[0])).toFloat();op.setSkew(if(m.groupValues[1]=="skewX")t else 0f,if(m.groupValues[1]=="skewY")t else 0f)}
                else->error("不支持的 SVG transform")
            };result.preConcat(op)
        };require(value.substring(end).all {it.isWhitespace()});ArtShapes.matrix(ArtShapes.encode(result));return result
    }
    fun color(value:String):String {
        val s=value.trim().lowercase();if(s=="none")return "#00000000"
        val named=mapOf("black" to "#000000","white" to "#ffffff","red" to "#ff0000","green" to "#008000","blue" to "#0000ff","transparent" to "#00000000")
        val h=named[s] ?: s
        require(h.matches(Regex("#[a-f0-9]{3}|#[a-f0-9]{6}|#[a-f0-9]{8}"))) {"颜色须为 none、#RGB、#RRGGBB 或 SVG2 #RRGGBBAA"}
        return when(h.length){4->"#ff"+h.substring(1).flatMap {listOf(it,it)}.joinToString("");7->"#ff"+h.substring(1);else->"#"+h.substring(7,9)+h.substring(1,7)}
    }
    fun css(color:String)="#"+color.substring(3)+color.substring(1,3)
    private fun path(source:String):JSONObject {
        val parts=mutableListOf<ArtPathTopology.Part>();var current:ArtPathTopology.Part?=null
        var point=ArtPathGeometry.Vec(0.0,0.0);var previous='M';var control=point
        fun finish(){current?.let {p->
            if(p.closed&&p.nodes.size>1&&(p.nodes.first().point-p.nodes.last().point).length()<1e-6){p.nodes.first().incoming=p.nodes.last().incoming;p.nodes.removeAt(p.nodes.lastIndex)}
            require(p.nodes.isNotEmpty()&&(p.closed||p.nodes.size>=2)){"SVG 子路径至少两个节点"};parts.add(p)
        };current=null}
        fun line(to:ArtPathGeometry.Vec){requireNotNull(current).nodes.add(ArtPathGeometry.Node(to));point=to}
        fun cubic(a:ArtPathGeometry.Vec,b:ArtPathGeometry.Vec,to:ArtPathGeometry.Vec){val nodes=requireNotNull(current).nodes;nodes.last().outgoing=a;nodes.add(ArtPathGeometry.Node(to,incoming=b));point=to;control=b}
        for(c in ArtSvgPath.commands(source)) {
            val v=c.values;val op=c.name.uppercaseChar();val relative=c.name.isLowerCase()
            fun vec(i:Int)=ArtPathGeometry.Vec(v[i],v[i+1])+(if(relative)point else ArtPathGeometry.Vec(0.0,0.0))
            require(current?.closed!=true || op=='M'){"Z 后须以 M 开始新子路径"}
            when(op) {
                'M'->{finish();point=vec(0);current=ArtPathTopology.Part(mutableListOf(ArtPathGeometry.Node(point)),false)}
                'L'->line(vec(0))
                'H'->line(ArtPathGeometry.Vec(v[0]+if(relative)point.x else 0.0,point.y))
                'V'->line(ArtPathGeometry.Vec(point.x,v[0]+if(relative)point.y else 0.0))
                'C'->cubic(vec(0),vec(2),vec(4))
                'S'->cubic(if(previous in setOf('C','S'))point*2.0-control else point,vec(0),vec(2))
                'Q','T'->{val a=if(op=='Q')vec(0)else if(previous in setOf('Q','T'))point*2.0-control else point;val b=vec(if(op=='Q')2 else 0);cubic(point+(a-point)*(2.0/3),b+(a-b)*(2.0/3),b);control=a}
                'Z'->{val p=requireNotNull(current);p.closed=true;point=p.nodes.first().point}
                else->error("SVG 图形路径支持 M/L/H/V/C/S/Q/T/Z；圆弧请使用 ellipse 或三次曲线")
            };previous=op
        };finish();return ArtPathTopology.geometry(parts)
    }
    fun pathCode(shape:JSONObject):String=ArtPathTopology.parts(shape).joinToString(" ") {part->
        val nodes=part.nodes;buildString {
            append("M ${nodes[0].point.x} ${nodes[0].point.y}")
            for(i in 0 until ArtPathGeometry.segmentCount(nodes,part.closed)) {
                val a=nodes[i];val b=nodes[(i+1)%nodes.size]
                if(a.outgoing==null&&b.incoming==null)append(" L ${b.point.x} ${b.point.y}")
                else {val u=a.outgoing ?: a.point;val v=b.incoming ?: b.point;append(" C ${u.x} ${u.y} ${v.x} ${v.y} ${b.point.x} ${b.point.y}")}
            };if(part.closed)append(" Z")
        }
    }
    fun shape(e:Element,old:JSONObject?,paint:(String)->JSONObject):JSONObject {
        val out=old?.let {JSONObject(it.toString())} ?: JSONObject().put("id",UUID.randomUUID().toString())
        val geometry=when(e.localName) {
            "rect"->{val x=number(e,"x");val y=number(e,"y");val w=number(e,"width");val h=number(e,"height");require(w>0&&h>0);require(!e.hasAttribute("ry")||number(e,"ry")==number(e,"rx"));JSONObject().put("kind","rectangle").put("points",JSONArray(listOf(listOf(x,y),listOf(x+w,y+h)))).put("cornerRadius",number(e,"rx"))}
            "ellipse","circle"->{val x=number(e,"cx");val y=number(e,"cy");val a=number(e,if(e.localName=="circle")"r" else "rx");val b=number(e,if(e.localName=="circle")"r" else "ry");require(a>0&&b>0);JSONObject().put("kind","ellipse").put("points",JSONArray(listOf(listOf(x-a,y-b),listOf(x+a,y+b))))}
            "line"->JSONObject().put("kind","line").put("points",JSONArray(listOf(listOf(number(e,"x1"),number(e,"y1")),listOf(number(e,"x2"),number(e,"y2")))))
            "polygon","polyline"->{val p=numbers(e.getAttribute("points"));require(p.size%2==0&&p.size>=4);val pts=JSONArray(p.chunked(2));if(e.localName=="polygon")JSONObject().put("kind","polygon").put("points",pts) else JSONObject().put("kind","path").put("points",pts).put("commands",JSONArray(List(pts.length()-1){"L"})).put("closed",false)}
            "path"->path(e.getAttribute("d")).put("kind","path")
            else->error("不支持的 SVG 图形")
        }
        val oldPath=old?.takeIf {it.getString("kind")=="path"&&e.localName=="path"}?.let {pathCode(it)}
        if(oldPath!=e.getAttribute("d") || e.localName!="path") {for(k in listOf("commands","closed","nodeModes","cornerRadius"))out.remove(k);geometry.keys().forEach {out.put(it,geometry.get(it))}}
        val style=ArtObjectStyle.defaults()
        for(k in listOf("fill","stroke")) {
            val value=e.getAttribute(k).ifBlank {if(k=="fill")"#000000" else "none"}
            if(value.startsWith("url(")){out.put(k,"#ff000000");style.put(k+"Gradient",paint(value))}else out.put(k,color(value))
            style.put(k+"Opacity",number(e,k+"-opacity",1.0))
        }
        style.put("fillRule",e.getAttribute("fill-rule").ifBlank {"nonzero"}).put("strokeCap",e.getAttribute("stroke-linecap").ifBlank {"butt"})
            .put("strokeJoin",e.getAttribute("stroke-linejoin").ifBlank {"miter"}).put("miterLimit",number(e,"stroke-miterlimit",4.0)).put("dashOffset",number(e,"stroke-dashoffset"))
        val dash=e.getAttribute("stroke-dasharray");style.put("dashArray",JSONArray(if(dash.isBlank()||dash=="none")emptyList<Double>() else numbers(dash)))
        out.put("objectStyle",style).put("strokeWidth",number(e,"stroke-width",1.0).also {require(it in .1..512.0)})
            .put("opacity",number(e,"opacity",1.0)).put("visible",e.getAttribute("display")!="none").put("matrix",ArtShapes.encode(transform(e.getAttribute("transform"))))
        return ArtShapes.normalize(out)
    }
}
