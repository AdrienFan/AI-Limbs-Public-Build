package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Versioned local-pixel gradient strokes share one kernel across preview, replay, thumbnails and export. */
internal object ArtGradient {
    const val MAX_PIXELS=4_194_304
    const val MAX_STOPS=16
    val modes=linkedMapOf("linear" to "线性","bilinear" to "双线性","radial" to "径向","square" to "方形",
        "angular" to "锥形","symmetric_conical" to "对称锥形","spiral" to "正螺旋","reverse_spiral" to "反螺旋","shape" to "按选区轮廓")
    val repeats=linkedMapOf("none" to "不重复","forward" to "正向重复","alternate" to "交替重复")
    val interpolations=linkedMapOf("srgb" to "sRGB","linear_rgb" to "线性RGB")
    data class Stop(val position:Double,val color:Int)
    private fun color(value:String):Int {require(value.matches(Regex("#[A-Fa-f0-9]{8}"))) {"色标须为#AARRGGBB"};return value.drop(1).toLong(16).toInt()}
    fun stops(input:JSONArray):List<Stop> {
        require(input.length() in 2..MAX_STOPS) {"渐变需要2–16个色标"}
        val out=(0 until input.length()).map {i->val a=input.getJSONArray(i);require(a.length()==2)
            val p=a.get(0);require(p is Number&&p.toDouble().isFinite()&&p.toDouble() in 0.0..1.0)
            Stop(p.toDouble(),color(a.getString(1)))}
        require(out.first().position==0.0&&out.last().position==1.0&&out.zipWithNext().all {it.first.position<it.second.position}) {"色标位置须严格递增且包含0和1"}
        return out
    }
    fun defaults()=JSONObject().put("gradientMode","linear").put("gradientRepeat","none").put("gradientReverse",false)
        .put("gradientDither",false).put("gradientSeed",0).put("gradientInterpolation","srgb").put("gradientAntialias",0.0)
    fun settings(p:JSONObject):JSONObject {
        val out=defaults();out.keys().forEach {if(p.has(it))out.put(it,p.get(it))}
        require(out.getString("gradientMode") in modes&&out.getString("gradientRepeat") in repeats&&out.getString("gradientInterpolation") in interpolations)
        for(k in listOf("gradientReverse","gradientDither"))require(out.get(k) is Boolean)
        val seed=out.get("gradientSeed");require(seed is Number&&seed.toDouble()==seed.toInt().toDouble()&&seed.toInt()>=0)
        val aa=out.get("gradientAntialias");require(aa is Number&&aa.toDouble().isFinite()&&aa.toDouble() in 0.0..1.0)
        val foreground=p.optString("color","#FF000000");color(foreground)
        val end=p.optString("gradientEndColor","#00"+foreground.substring(3));color(end)
        val raw=if(p.has("gradientStops"))p.getJSONArray("gradientStops") else JSONArray().put(JSONArray().put(0).put(foreground)).put(JSONArray().put(1).put(end))
        stops(raw);out.put("gradientStops",JSONArray(raw.toString()))
        return out
    }
    fun info()=JSONObject().put("defaults",defaults()).put("shapes",JSONObject(modes)).put("repeats",JSONObject(repeats))
        .put("interpolations",JSONObject(interpolations)).put("maxStops",MAX_STOPS).put("maxPixels",MAX_PIXELS)
        .put("scope","绘画层局部像素，含可编辑组内/变换层；两点定义方向与尺度。shape需已有非空选区，以非零覆盖轮廓的欧氏距离按每个四连通岛归一化，洞和凹边参与边界；边缘为末色、最深处为首色。与Krita轮廓策略不逐像素等同。")
        .put("pipeline","shape -> repeat -> reverse -> premultiplied multistop interpolation -> seeded RGB quantization dither -> opacity × frozen soft selection once")
    private fun bounds(p:JSONObject):Rect {
        val b=p.getJSONObject("gradientBounds");val x=b.getInt("x");val y=b.getInt("y");val w=b.getInt("width");val h=b.getInt("height")
        require(w>0&&h>0&&w.toLong()*h<=MAX_PIXELS&&abs(x.toLong())<=1_000_000&&abs(y.toLong())<=1_000_000)
        return Rect(x,y,x+w,y+h)
    }
    fun prepare(p:JSONObject,state:JSONObject):JSONObject {
        val out=JSONObject(p.toString());out.optJSONObject("selection")?.apply {remove("curveParts");remove("curveBasis")}
        val config=settings(out);config.keys().forEach {out.put(it,config.get(it))}
        val points=out.getJSONArray("points");require(points.length()==2)
        for(i in 0..1) {val point=points.getJSONArray(i);require(point.length() in 2..3)
            for(k in 0..1)require(point.getDouble(k).isFinite()&&abs(point.getDouble(k))<=1_000_000)}
        val a=points.getJSONArray(0);val b=points.getJSONArray(1)
        require(hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1))>=0.01) {"请拖出渐变方向"}
        val w=state.getInt("width");val h=state.getInt("height");var r=Rect(0,0,w,h)
        val selection=out.optJSONObject("selection")
        require(config.getString("gradientMode")!="shape"||selection!=null) {"轮廓渐变请先建立选区"}
        selection?.let {s->val path=ArtSelection.path(s);path.transform(ArtShapes.matrix(out.getJSONArray("selectionToLayer")))
            val box=RectF();path.computeBounds(box,true)
            r=Rect(floor(box.left.toDouble()).toInt().coerceIn(0,w),floor(box.top.toDouble()).toInt().coerceIn(0,h),
                ceil(box.right.toDouble()).toInt().coerceIn(0,w),ceil(box.bottom.toDouble()).toInt().coerceIn(0,h))}
        require(!r.isEmpty&&r.width().toLong()*r.height()<=MAX_PIXELS) {"渐变范围需非空且最多4194304局部像素；请用选区缩小范围"}
        out.put("gradientVersion",1).put("gradientBounds",JSONObject().put("x",r.left).put("y",r.top).put("width",r.width()).put("height",r.height()))
        validateStored(out);ArtImagePolicy.requireBytes(overhead(out),"渐变求解")
        return out
    }
    fun validateStored(p:JSONObject) {
        require(p.getInt("gradientVersion")==1&&p.getString("tool")=="gradient")
        settings(p);bounds(p)
        val points=p.getJSONArray("points");require(points.length()==2)
        for(i in 0..1) {val point=points.getJSONArray(i);require(point.length() in 2..3)
            for(k in 0..1)require(point.getDouble(k).isFinite()&&abs(point.getDouble(k))<=1_000_000)}
        val a=points.getJSONArray(0);val b=points.getJSONArray(1)
        require(hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1))>=0.01)
        val opacity=p.optDouble("opacity",1.0);require(opacity.isFinite()&&opacity in 0.0..1.0)
        require(p.getString("gradientMode")!="shape"||p.optJSONObject("selection")!=null)
        p.optJSONObject("selection")?.let {ArtSelection.validate(it);ArtShapes.matrix(p.getJSONArray("selectionToLayer"))}
    }
    private fun maskBytes(p:JSONObject):Long=p.optJSONObject("selection")?.takeIf {it.has("coverage")}?.let {it.getInt("maskWidth").toLong()*it.getInt("maskHeight")} ?: 0L
    fun overhead(p:JSONObject):Long {val r=bounds(p);return maskBytes(p)+(r.width()+2L)*(r.height()+2)*(if(p.getString("gradientMode")=="shape")64 else 16)}
    private val linear=DoubleArray(256) {val c=it/255.0;if(c<=0.04045)c/12.92 else ((c+0.055)/1.055).pow(2.4)}
    private fun encodeLinear(c:Double):Double=(if(c<=0.0031308)c*12.92 else 1.055*c.pow(1/2.4)-0.055)*255
    fun interpolate(stops:List<Stop>,t:Double,interpolation:String):DoubleArray=DoubleArray(4).also {interpolateInto(stops,t,interpolation,it)}
    private fun interpolateInto(stops:List<Stop>,t:Double,interpolation:String,result:DoubleArray) {
        require(t.isFinite()&&t in 0.0..1.0&&interpolation in interpolations)
        var lo=0;var hi=stops.lastIndex
        while(hi-lo>1) {val mid=(lo+hi)/2;if(t<stops[mid].position)hi=mid else lo=mid}
        val a=stops[lo];val b=stops[hi];val u=((t-a.position)/(b.position-a.position)).coerceIn(0.0,1.0)
        val aa=(a.color ushr 24)/255.0;val ba=(b.color ushr 24)/255.0;val alpha=aa*(1-u)+ba*u
        if(alpha==0.0) {result.fill(0.0);return}
        fun channel(shift:Int):Double {val x=(a.color ushr shift) and 255;val y=(b.color ushr shift) and 255
            val v=((if(interpolation=="linear_rgb")linear[x] else x.toDouble())*aa*(1-u)+(if(interpolation=="linear_rgb")linear[y] else y.toDouble())*ba*u)/alpha
            return if(interpolation=="linear_rgb")encodeLinear(v) else v}
        result[0]=alpha*255;result[1]=channel(16);result[2]=channel(8);result[3]=channel(0)
    }
    data class Image(val bitmap:Bitmap,val bounds:Rect)
    fun image(p:JSONObject,previewEdge:Int=0):Image {
        validateStored(p);val r=bounds(p)
        require(previewEdge==0||previewEdge in 1..512)
        val step=if(previewEdge==0)1 else maxOf(1,ceil(maxOf(r.width(),r.height()).toDouble()/previewEdge).toInt())
        val w=(r.width()+step-1)/step;val h=(r.height()+step-1)/step
        ArtImagePolicy.requireBytes(maskBytes(p)+(w+2L)*(h+2)*(if(p.getString("gradientMode")=="shape")64 else 16),"渐变像素")
        val selection=p.optJSONObject("selection");val sampler=selection?.let {ArtSoftSelection.Sampler(it)}
        val matrix=FloatArray(9)
        if(sampler!=null) {val inverse=Matrix();require(ArtShapes.matrix(p.getJSONArray("selectionToLayer")).invert(inverse));inverse.getValues(matrix)}
        val coverage=ByteArray(w*h) {i->if(sampler==null)255.toByte() else {
            val x=r.left+(i%w+0.5)*r.width()/w;val y=r.top+(i/w+0.5)*r.height()/h
            sampler.at(matrix[0]*x+matrix[1]*y+matrix[2],matrix[3]*x+matrix[4]*y+matrix[5]).toByte()}}
        val mode=p.getString("gradientMode");val contour=if(mode=="shape")ArtGradientMath.contour(coverage,w,h) else null
        val points=p.getJSONArray("points");val a=points.getJSONArray(0);val b=points.getJSONArray(1)
        val x0=a.getDouble(0);val y0=a.getDouble(1);val x1=b.getDouble(0);val y1=b.getDouble(1)
        val stops=stops(p.getJSONArray("gradientStops"));val repeat=p.getString("gradientRepeat");val reverse=p.getBoolean("gradientReverse")
        val interpolation=p.getString("gradientInterpolation");val opacity=p.optDouble("opacity",1.0)
        val dither=p.getBoolean("gradientDither");val seed=p.getInt("gradientSeed");val aa=p.getDouble("gradientAntialias")
        val output=IntArray(w*h)
        val field=ArtGradientMath.Field(mode,x0,y0,x1,y1);val spiral=mode=="spiral"||mode=="reverse_spiral"
        fun at(x:Double,y:Double,shapeValue:Double,result:DoubleArray) {
            val raw=if(mode=="shape")shapeValue else field.value(x,y)
            val value=ArtGradientMath.repeat(raw,repeat,spiral)
            interpolateInto(stops,if(reverse)1-value else value,interpolation,result)
        }
        val c=DoubleArray(4);val sample=DoubleArray(4);val sum=DoubleArray(4)
        for(i in output.indices) {
            val cover=coverage[i].toInt() and 255;if(cover==0)continue
            val x=r.left+(i%w+0.5)*r.width()/w;val y=r.top+(i/w+0.5)*r.height()/h
            val shapeValue=contour?.get(i)?.toDouble() ?: 0.0
            at(x,y,shapeValue,c)
            if(aa>0&&mode!="shape") {
                sum.fill(0.0)
                for(oy in 0..1)for(ox in 0..1) {
                    at(x+(if(ox==0)-0.25 else 0.25),y+(if(oy==0)-0.25 else 0.25),shapeValue,sample);sum[0]+=sample[0]
                    for(k in 1..3)sum[k]+=sample[k]*sample[0]
                }
                val sa=sum[0]/4;val alpha=c[0]*(1-aa)+sa*aa
                for(k in 1..3)c[k]=if(alpha==0.0)0.0 else (c[k]*c[0]*(1-aa)+sum[k]/4*aa)/alpha
                c[0]=alpha
            }
            val alpha=(c[0]*opacity*cover/255).roundToInt().coerceIn(0,255)
            fun channel(k:Int)=floor(c[k]+0.5+(if(dither)ArtGradientMath.noise(floor(x).toInt(),floor(y).toInt(),seed,k) else 0.0)).toInt().coerceIn(0,255)
            output[i]=if(alpha==0)0 else (alpha shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
        }
        return Image(Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888),r)
    }
    fun draw(canvas:Canvas,p:JSONObject,previewEdge:Int=0) {
        val result=image(p,previewEdge)
        try {canvas.drawBitmap(result.bitmap,null,RectF(result.bounds),Paint(Paint.FILTER_BITMAP_FLAG))} finally {result.bitmap.recycle()}
    }
}
