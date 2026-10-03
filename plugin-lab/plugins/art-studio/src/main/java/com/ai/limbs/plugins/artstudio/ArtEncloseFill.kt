package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Independent RGBA8 enclosing/region solver. Only the final overlay enters document history. */
internal object ArtEncloseFill {
    const val MAX_POINTS=2048
    const val MAX_PIXELS=4_194_304
    val shapes=linkedMapOf("lasso" to "自由套索","rect" to "矩形","ellipse" to "椭圆","brush" to "画笔涂抹","bezier" to "贝塞尔曲线")
    val modes=linkedMapOf("all" to "全部区域","transparent" to "透明区域","color" to "指定颜色",
        "color_or_transparent" to "指定颜色或透明","not_color" to "排除指定颜色",
        "not_transparent" to "排除透明","not_color_or_transparent" to "排除指定颜色及透明")
    val pending=listOf("按外围轮廓颜色判定","变换及组内图层写入","HDR 与动画")
    fun defaults()=JSONObject().put("shape","lasso").put("mode","all")
        .put("regionColor","#FFFFFFFF").put("tolerance",15).put("includeContour",false)
        .put("invert",false).put("reference","visible").put("width",32)
        .put("opacity",1.0).put("erase",false).put("expand",0).put("feather",0).put("gapClose",0)
        .put("opacitySpread",100).put("antialias",1).put("stopAtDarkest",false).put("colorLabels",JSONArray().put(1))
        .put("fillType","solid").put("blend","normal")
        .put("pattern",ArtFigure.fill(JSONObject().put("mode","pattern"),"#FF161616").getJSONObject("pattern"))
    fun info()=JSONObject().put("defaults",defaults()).put("shapes",JSONObject(shapes)).put("modes",JSONObject(modes))
        .put("maxPoints",MAX_POINTS).put("maxRegionPixels",MAX_PIXELS).put("coordinateSpace","document")
        .put("patterns",JSONObject(ArtFigure.patterns)).put("blends",JSONObject(ArtPixelBlend.names)).put("labels",ArtLayerLabels.info())
        .put("pipeline","围合分区及颜色软覆盖 -> 反选 -> 扩缩/最暗停止 -> 羽化或AA -> min现有选区 -> 纯色/变换图案 -> 目标层内混合")
        .put("pending",JSONArray(pending)).put("gapScope","仅颜色／透明条件；形态学开运算断开窄通道，再恢复选中区域边缘")
        .put("scope","当前未锁定的可见、未变换根绘画或图像层；参考可选当前层原始像素、可见图层或颜色标签层合成，不含文档背景及辅助对象")
    fun options(input: JSONObject): JSONObject {
        val p=defaults()
        for(key in p.keys().asSequence().toList()) if(input.has(key))p.put(key,input.get(key))
        require(p.getString("shape") in shapes && p.getString("mode") in modes)
        val shared=JSONObject(p.toString()).put("mode","replace")
        ArtColorSelection.options(shared)
        require(p.getString("regionColor").matches(Regex("#[a-fA-F0-9]{8}")))
        require(p.getString("fillType") in setOf("solid","pattern"))
        ArtPixelBlend.validate(p.getString("blend"))
        require(!p.getBoolean("erase") || p.getString("blend")=="normal") {"擦除固定使用DST_OUT，请将blend设为normal"}
        val opacity=p.get("opacity");require(opacity is Number && opacity.toDouble().isFinite() && opacity.toDouble() in 0.0..1.0)
        for(key in listOf("includeContour","invert","erase"))require(p.get(key) is Boolean)
        val width=p.get("width");require(width is Number && width.toDouble()==width.toInt().toDouble() && width.toInt() in 1..256)
        p.put("pattern",ArtFigure.fill(JSONObject().put("mode","pattern").put("pattern",p.getJSONObject("pattern")),input.optString("color","#FF161616")).getJSONObject("pattern"))
        require(p.getString("mode")!="all" || p.getInt("gapClose")==0) {"全部区域模式暂不支持缺口闭合，请选择颜色或透明条件"}
        return p
    }
    fun target(layer: JSONObject) {
        require(layer.getString("kind") in setOf("paint","image") && layer.getBoolean("visible") &&
            !layer.getBoolean("locked")) {"请选择未锁定的可见绘画或图像图层"}
        require(layer.optString("parentId").isBlank() && layer.getDouble("x")==0.0 && layer.getDouble("y")==0.0 &&
            layer.getDouble("scale")==1.0 && !layer.has("affine") && layer.getDouble("rotation")==0.0) {"基础围合填充需要未变换的根图层"}
    }
    fun points(input: JSONArray): List<Pair<Float,Float>> {
        require(input.length() in 1..MAX_POINTS) {"围合路径需要1–2048点"}
        return (0 until input.length()).map {
            val q=input.getJSONArray(it);require(q.length()==2)
            val x=q.getDouble(0);val y=q.getDouble(1)
            require(x.isFinite() && y.isFinite() && abs(x)<=1_000_000 && abs(y)<=1_000_000) {"围合坐标无效"}
            x.toFloat() to y.toFloat()
        }
    }
    fun path(p: JSONObject,points: List<Pair<Float,Float>>): Path {
        val shape=p.getString("shape")
        if(shape=="bezier") {
            val nodes=nodes(p.getJSONArray("nodes"))
            return ArtPathGeometry.preview(nodes,true).apply {fillType=Path.FillType.EVEN_ODD}
        }
        return Path().apply {
            when(shape) {
                "rect","ellipse" -> {
                    require(points.size==2) {"矩形和椭圆围合需要两个对角点"}
                    val a=points.first();val b=points.last()
                    val bounds=RectF(minOf(a.first,b.first),minOf(a.second,b.second),maxOf(a.first,b.first),maxOf(a.second,b.second))
                    require(bounds.width()>0 && bounds.height()>0) {"围合面积为零"}
                    if(shape=="rect")addRect(bounds,Path.Direction.CW) else addOval(bounds,Path.Direction.CW)
                }
                "lasso" -> {
                    require(points.size>=3) {"套索至少需要三个点"}
                    fillType=Path.FillType.EVEN_ODD
                    points.forEachIndexed {i,q-> if(i==0)moveTo(q.first,q.second) else lineTo(q.first,q.second)}
                    close()
                }
                "brush" -> {
                    points.forEachIndexed {i,q->if(i==0)moveTo(q.first,q.second) else lineTo(q.first,q.second)}
                    val expanded=Path()
                    if(points.size==1)expanded.addCircle(points[0].first,points[0].second,p.getInt("width")/2f,Path.Direction.CW)
                    else Paint().apply {style=Paint.Style.STROKE;strokeWidth=p.getInt("width").toFloat()
                        strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}.getFillPath(this,expanded)
                    set(expanded)
                }
            }
        }
    }
    fun nodes(input:JSONArray):MutableList<ArtPathGeometry.Node> {
        require(input.length() in 2..MAX_POINTS) {"贝塞尔围合需要2–2048节点"}
        return ArtPathGeometry.parse(input)
    }
    fun geometry(input:JSONObject,p:JSONObject):JSONObject {
        val out=JSONObject(p.toString())
        if(p.getString("shape")=="bezier") {
            require(!input.has("points")) {"贝塞尔使用nodes，其他围合使用points"}
            out.put("nodes",ArtPathGeometry.json(nodes(input.getJSONArray("nodes"))))
        } else {
            require(!input.has("nodes")) {"只有贝塞尔围合使用nodes"}
            out.put("points",JSONArray().apply {points(input.getJSONArray("points")).forEach {put(JSONArray().put(it.first).put(it.second))}})
        }
        return out
    }
    data class Area(val rect: Rect,val path: Path)
    fun area(state: JSONObject,p: JSONObject,points: List<Pair<Float,Float>>): Area {
        val shape=path(p,points);val bounds=RectF();shape.computeBounds(bounds,true)
        // Reserve room for growth/feather/AA so the original bounding box does not truncate soft effects.
        val pad=maxOf(0,p.getInt("expand"))+p.getInt("feather")+1
        val left=(floor(bounds.left.toDouble()).toInt()-pad).coerceAtLeast(0)
        val top=(floor(bounds.top.toDouble()).toInt()-pad).coerceAtLeast(0)
        val right=(ceil(bounds.right.toDouble()).toInt()+pad).coerceAtMost(state.getInt("width"))
        val bottom=(ceil(bounds.bottom.toDouble()).toInt()+pad).coerceAtMost(state.getInt("height"))
        require(right>left && bottom>top) {"围合范围没有画布交集"}
        val rect=Rect(left,top,right,bottom)
        require(rect.width().toLong()*rect.height()<=MAX_PIXELS) {"围合及软边范围超过4194304像素，请缩小范围"}
        return Area(rect,shape)
    }
    data class Result(val bitmap: Bitmap,val pixels: Int,val regions: Int)
    /** Same distance ramp as the shared color-selection engine; complementary conditions retain soft coverage. */
    fun ramp(d:Int,threshold:Int,spread:Int):Int {
        require(d in 0..255 && threshold in 0..255 && spread in 0..100 && (spread==100 || threshold>0))
        if(spread==100)return if(d<=threshold)255 else 0
        return if(d<threshold)((threshold-d)*255L*100/(threshold*(100-spread))).toInt().coerceIn(0,255) else 0
    }
    fun condition(mode:String,colorCoverage:Int,transparentCoverage:Int):Int=when(mode) {
        "all"->255;"transparent"->transparentCoverage;"color"->colorCoverage
        "color_or_transparent"->maxOf(colorCoverage,transparentCoverage)
        "not_color"->255-colorCoverage;"not_transparent"->255-transparentCoverage
        "not_color_or_transparent"->255-maxOf(colorCoverage,transparentCoverage)
        else->error("围合颜色条件无效")
    }
    fun solve(state: JSONObject,source: Bitmap,p: JSONObject,area: Area,color: Int,resources:((String)->Bitmap)?=null): Result {
        val rect=area.rect;val w=rect.width();val h=rect.height();val n=w*h
        val reference=IntArray(n);source.getPixels(reference,0,w,rect.left,rect.top,w,h)
        val outline=Region().apply {setPath(area.path,Region(rect))}
        val inside=ByteArray(n) {i->if(outline.contains(rect.left+i%w,rect.top+i/w))255.toByte() else 0}
        require(inside.any {it.toInt()!=0}) {"围合面积不足一个像素"}
        val mode=p.getString("mode");val all=mode=="all";val threshold=p.getInt("tolerance")*255/100
        val wanted=Color.parseColor(p.getString("regionColor"));val spread=p.getInt("opacitySpread")
        val candidate=ByteArray(n) {i->if(inside[i].toInt()==0)0 else condition(mode,
            ramp(ArtColorSelection.difference(reference[i],wanted),threshold,spread),
            ramp(Color.alpha(reference[i]),threshold,spread)).toByte()}
        val membership=ByteArray(n) {i->if(candidate[i].toInt()!=0)255.toByte() else 0}
        val gap=p.getInt("gapClose")
        val core=if(gap==0)membership else ArtColorSelection.morph(ArtColorSelection.morph(membership,w,h,gap,false),w,h,gap,true)
        val interior=ArtColorSelection.morph(inside,w,h,gap+1,false)
        val visited=BooleanArray(n);val queue=IntArray(n);var selected=ByteArray(n);var regions=0
        for(seed in 0 until n) {
            if(core[seed].toInt()==0 || visited[seed])continue
            var head=0;var tail=1;queue[0]=seed;visited[seed]=true;var contour=false
            while(head<tail) {
                val i=queue[head++];if(interior[i].toInt()==0)contour=true
                fun visit(j: Int) {
                    if(!visited[j] && core[j].toInt()!=0 && (!all || ArtColorSelection.difference(reference[j],reference[seed])<=threshold)) {
                        visited[j]=true;queue[tail++]=j
                    }
                }
                val x=i%w;val y=i/w
                if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
            }
            if(p.getBoolean("includeContour") || !contour) {
                regions++;for(k in 0 until tail) {val i=queue[k];selected[i]=if(all)
                    ramp(ArtColorSelection.difference(reference[i],reference[seed]),threshold,spread).toByte() else candidate[i]}
            }
        }
        if(gap>0) {
            selected=ArtColorSelection.morph(selected,w,h,gap,true)
            for(i in 0 until n)selected[i]=minOf(selected[i].toInt() and 255,candidate[i].toInt() and 255).toByte()
        }
        if(p.getBoolean("invert"))for(i in 0 until n)selected[i]=if(inside[i].toInt()!=0)(255-(selected[i].toInt() and 255)).toByte() else 0
        val effects=JSONObject(p.toString()).put("mode","replace").put("limitToSelection",false)
        val mask=ArtColorSelection.finish(state,source,ArtColorSelection.Mask(selected,rect,wanted),effects)
        state.optJSONObject("selection")?.let {selection->
            val sampler=ArtSoftSelection.Sampler(selection)
            for(i in 0 until n)mask.alpha[i]=minOf(mask.alpha[i].toInt() and 255,
                sampler.at(rect.left+i%w+0.5,rect.top+i/w+0.5)).toByte()
        }
        val paint=JSONObject(p.toString()).put("color",String.format("#%08X",color))
        val bitmap=ArtContiguousFill.paint(mask,paint,resources)
        try {
            val row=IntArray(w);var filled=0
            for(y in 0 until h) {bitmap.getPixels(row,0,w,0,y,w,1);filled+=row.count {Color.alpha(it)>0}}
            return Result(bitmap,filled,regions)
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }
}
