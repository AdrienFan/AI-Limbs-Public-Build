package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Object-local style is shared by painting, hit coverage and saved vector geometry. */
internal object ArtObjectStyle {
    val caps=linkedMapOf("butt" to "平头","round" to "圆头","square" to "方头")
    val joins=linkedMapOf("miter" to "尖角","round" to "圆角","bevel" to "斜角")
    val keys=setOf("fillRule","strokeCap","strokeJoin","miterLimit","dashArray","dashOffset","fillOpacity","strokeOpacity","fillGradient","strokeGradient")
    fun defaults()=JSONObject().put("fillRule","nonzero").put("strokeCap","round").put("strokeJoin","round").put("miterLimit",4)
        .put("dashArray",JSONArray()).put("dashOffset",0).put("fillOpacity",1).put("strokeOpacity",1)
    fun settings(shape:JSONObject):JSONObject {
        val out=defaults()
        if(shape.has("objectStyle")) {val patch=shape.getJSONObject("objectStyle");require(patch.keys().asSequence().all {it in keys});patch.keys().forEach {out.put(it,patch.get(it))}}
        require(out.getString("fillRule") in setOf("nonzero","evenodd")){"fill-rule 只支持nonzero/evenodd"}
        require(out.getString("strokeCap") in caps){"stroke-linecap 只支持butt/round/square"}
        require(out.getString("strokeJoin") in joins){"stroke-linejoin 只支持miter/round/bevel"}
        val m=out.getDouble("miterLimit");require(m.isFinite()&&m in 1.0..100.0){"stroke-miterlimit 须为1..100"}
        val d=out.getJSONArray("dashArray");require(d.length()==0||d.length() in 2..16&&d.length()%2==0){"stroke-dasharray 须为空或2..16个数字，数量为偶数"}
        for(i in 0 until d.length())require(d.getDouble(i).isFinite()&&d.getDouble(i) in 0.1..4096.0){"stroke-dasharray 第${i+1}项须为0.1..4096"}
        val offset=out.getDouble("dashOffset");require(offset.isFinite()&&abs(offset)<=1000000)
        for(k in listOf("fillOpacity","strokeOpacity"))require(out.getDouble(k).isFinite()&&out.getDouble(k) in 0.0..1.0){"$k 须为0..1"}
        for(k in listOf("fillGradient","strokeGradient"))if(out.has(k)&&!out.isNull(k))gradient(out.getJSONObject(k))
        return out
    }
    private fun gradient(g:JSONObject) {
        require(g.keys().asSequence().all {it in setOf("type","start","end","stops")});require(g.getString("type") in setOf("linear","radial"))
        val a=g.getJSONArray("start");val b=g.getJSONArray("end")
        require(a.length()==2&&b.length()==2&&(0..1).all {a.getDouble(it).isFinite()&&b.getDouble(it).isFinite()&&abs(a.getDouble(it))<=1000000&&abs(b.getDouble(it))<=1000000})
        require(hypot(a.getDouble(0)-b.getDouble(0),a.getDouble(1)-b.getDouble(1))>0.001)
        val stops=g.getJSONArray("stops");require(stops.length() in 2..16);var previous=-1.0
        for(i in 0 until stops.length()) {val stop=stops.getJSONArray(i);require(stop.length()==2);val n=stop.getDouble(0);require(n.isFinite()&&n in 0.0..1.0&&n>previous);previous=n;require(stop.getString(1).matches(Regex("#[A-Fa-f0-9]{8}")))}
        require(stops.getJSONArray(0).getDouble(0)==0.0&&stops.getJSONArray(stops.length()-1).getDouble(0)==1.0)
    }
    fun paint(shape:JSONObject,stroke:Boolean,opacity:Boolean=true):Paint {
        val style=settings(shape);val key=if(stroke)"stroke" else "fill"
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL
            color=Color.parseColor(shape.getString(key));strokeWidth=shape.getDouble("strokeWidth").toFloat()
            strokeCap=when(style.getString("strokeCap")) {"butt"->Paint.Cap.BUTT;"round"->Paint.Cap.ROUND;"square"->Paint.Cap.SQUARE;else->error("线帽无效")}
            strokeJoin=when(style.getString("strokeJoin")) {"miter"->Paint.Join.MITER;"round"->Paint.Join.ROUND;"bevel"->Paint.Join.BEVEL;else->error("转角无效")}
            strokeMiter=style.getDouble("miterLimit").toFloat()
            if(stroke&&style.getJSONArray("dashArray").length()>0) {
                val d=style.getJSONArray("dashArray");pathEffect=DashPathEffect(FloatArray(d.length()) {d.getDouble(it).toFloat()},style.getDouble("dashOffset").toFloat())
            }
            val gradientKey=key+"Gradient"
            if(style.has(gradientKey)&&!style.isNull(gradientKey)) {
                val g=style.getJSONObject(gradientKey);val a=g.getJSONArray("start");val b=g.getJSONArray("end");val stops=g.getJSONArray("stops")
                val colors=IntArray(stops.length()) {Color.parseColor(stops.getJSONArray(it).getString(1))};val positions=FloatArray(stops.length()) {stops.getJSONArray(it).getDouble(0).toFloat()}
                shader=if(g.getString("type")=="linear")LinearGradient(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),b.getDouble(0).toFloat(),b.getDouble(1).toFloat(),colors,positions,Shader.TileMode.CLAMP)
                    else RadialGradient(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1)).toFloat(),colors,positions,Shader.TileMode.CLAMP)
                alpha=if(opacity)(255*style.getDouble(key+"Opacity")).roundToInt() else 255
            } else if(opacity)alpha=(Color.alpha(color)*style.getDouble(key+"Opacity")).roundToInt()
        }
    }
    fun visible(shape:JSONObject,stroke:Boolean):Boolean {
        val s=settings(shape);val key=if(stroke)"stroke" else "fill"
        if(s.getDouble(key+"Opacity")==0.0||shape.getDouble("opacity")==0.0)return false
        val g=key+"Gradient"
        if(s.has(g)&&!s.isNull(g)) {val stops=s.getJSONObject(g).getJSONArray("stops");return (0 until stops.length()).any {Color.alpha(Color.parseColor(stops.getJSONArray(it).getString(1)))>0}}
        return Color.alpha(Color.parseColor(shape.getString(key)))>0
    }
    fun info()=JSONObject().put("defaults",defaults()).put("caps",JSONObject(caps)).put("joins",JSONObject(joins))
        .put("gradient","fillGradient/strokeGradient: null clears; {type:linear/radial,start:[x,y],end:[x,y],stops:[[0,#AARRGGBB],...,[1,#AARRGGBB]]}; 2–16 strictly increasing stops, object-local pixels")
        .put("limits","miterLimit 1–100; dashArray [] or 2–16 even positive lengths 0.1–4096 object-local pixels; dashOffset ±1000000; opacity 0–1; fillRule nonzero/evenodd")
}
