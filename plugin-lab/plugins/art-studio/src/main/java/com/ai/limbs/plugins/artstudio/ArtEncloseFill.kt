package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.roundToInt

/** Independent RGBA8 enclosing/region solver. Only the final overlay enters document history. */
internal object ArtEncloseFill {
    const val MAX_POINTS=2048
    const val MAX_PIXELS=4_194_304
    val shapes=linkedMapOf("lasso" to "自由套索","rect" to "矩形","ellipse" to "椭圆","brush" to "画笔涂抹")
    val modes=linkedMapOf("all" to "全部区域","transparent" to "透明区域","color" to "指定颜色",
        "color_or_transparent" to "指定颜色或透明","not_color" to "排除指定颜色",
        "not_transparent" to "排除透明","not_color_or_transparent" to "排除指定颜色及透明")
    val pending=listOf("贝塞尔围合","按外围轮廓颜色判定","图案填充","颜色标签参考组",
        "最暗像素停止扩展","变换及组内图层写入","HDR 与动画")
    fun defaults()=JSONObject().put("shape","lasso").put("mode","all")
        .put("regionColor","#FFFFFFFF").put("tolerance",15).put("includeContour",false)
        .put("invert",false).put("reference","visible").put("width",32)
        .put("opacity",1.0).put("erase",false).put("expand",0).put("feather",0).put("gapClose",0)
    fun info()=JSONObject().put("defaults",defaults()).put("shapes",JSONObject(shapes)).put("modes",JSONObject(modes))
        .put("maxPoints",MAX_POINTS).put("maxRegionPixels",MAX_PIXELS).put("coordinateSpace","document")
        .put("pending",JSONArray(pending)).put("gapScope","仅颜色／透明条件；形态学开运算断开窄通道，再恢复选中区域边缘")
        .put("scope","当前未锁定的可见、未变换根绘画或图像层；参考可选当前层原始像素或可见图层合成，不含文档背景及辅助对象")
    fun options(input: JSONObject): JSONObject {
        val p=defaults()
        for(key in p.keys().asSequence().toList()) if(input.has(key))p.put(key,input.get(key))
        require(p.getString("shape") in shapes && p.getString("mode") in modes)
        require(p.getString("reference") in setOf("current","visible"))
        require(p.getString("regionColor").matches(Regex("#[a-fA-F0-9]{8}")))
        Color.parseColor(p.getString("regionColor"))
        for((key,range) in mapOf("tolerance" to 0..100,"width" to 1..256,
            "expand" to -16..16,"feather" to 0..8,"gapClose" to 0..8)) {
            val value=p.getDouble(key);require(value.isFinite() && value==value.toInt().toDouble() && value.toInt() in range) {"$key 超出整数范围"}
        }
        require(p.getDouble("opacity") in 0.0..1.0)
        for(key in listOf("includeContour","invert","erase"))p.getBoolean(key)
        require(p.getString("mode")!="all" || p.getInt("gapClose")==0) {"全部区域模式暂不支持缺口闭合，请选择颜色或透明条件"}
        return p
    }
    fun target(layer: JSONObject) {
        require(layer.getString("kind") in setOf("paint","image") && layer.getBoolean("visible") &&
            !layer.getBoolean("locked")) {"请选择未锁定的可见绘画或图像图层"}
        require(layer.optString("parentId").isBlank() && layer.getDouble("x")==0.0 && layer.getDouble("y")==0.0 &&
            layer.getDouble("scale")==1.0 && layer.getDouble("rotation")==0.0) {"基础围合填充需要未变换的根图层"}
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
    data class Area(val rect: Rect,val path: Path)
    fun area(state: JSONObject,p: JSONObject,points: List<Pair<Float,Float>>): Area {
        val shape=path(p,points);val bounds=RectF();shape.computeBounds(bounds,true)
        val left=floor(bounds.left.toDouble()).toInt().coerceAtLeast(0)
        val top=floor(bounds.top.toDouble()).toInt().coerceAtLeast(0)
        val right=ceil(bounds.right.toDouble()).toInt().coerceAtMost(state.getInt("width"))
        val bottom=ceil(bounds.bottom.toDouble()).toInt().coerceAtMost(state.getInt("height"))
        require(right>left && bottom>top) {"围合范围没有画布交集"}
        val rect=Rect(left,top,right,bottom)
        require(rect.width().toLong()*rect.height()<=MAX_PIXELS) {"单次围合范围超过4194304像素，请缩小范围"}
        return Area(rect,shape)
    }
    data class Result(val bitmap: Bitmap,val pixels: Int,val regions: Int)
    private fun difference(a: Int,b: Int): Int {
        // Premultiplied RGBA ignores invisible RGB values and includes transparency.
        val aa=Color.alpha(a);val ba=Color.alpha(b)
        return maxOf(abs(aa-ba),abs(Color.red(a)*aa/255-Color.red(b)*ba/255),
            abs(Color.green(a)*aa/255-Color.green(b)*ba/255),abs(Color.blue(a)*aa/255-Color.blue(b)*ba/255))
    }
    /** Square morphology in linear time. Outside the bounded image is transparent/zero. */
    private fun morph(input: ByteArray,w: Int,h: Int,r: Int,grow: Boolean): ByteArray {
        if(r==0)return input.copyOf()
        fun pass(src: ByteArray,horizontal: Boolean): ByteArray {
            val out=ByteArray(src.size);val length=if(horizontal)w else h;val lines=if(horizontal)h else w
            val deque=IntArray(length+2*r);val values=IntArray(length+2*r)
            for(line in 0 until lines) {
                var head=0;var tail=0
                fun at(k: Int): Int {
                    if(k<0 || k>=length)return 0
                    return src[if(horizontal)line*w+k else k*w+line].toInt() and 255
                }
                for(k in -r until length+r) {
                    val slot=k+r;val value=at(k);values[slot]=value
                    while(tail>head && if(grow)values[deque[tail-1]]<=value else values[deque[tail-1]]>=value)tail--
                    deque[tail++]=slot
                    val center=k-r
                    while(head<tail && deque[head]<center)head++
                    if(center in 0 until length)out[if(horizontal)line*w+center else center*w+line]=values[deque[head]].toByte()
                }
            }
            return out
        }
        return pass(pass(input,true),false)
    }
    private fun blur(input: ByteArray,w: Int,h: Int,r: Int): ByteArray {
        if(r==0)return input.copyOf()
        fun pass(src: ByteArray,horizontal: Boolean): ByteArray {
            val out=ByteArray(src.size);val length=if(horizontal)w else h;val lines=if(horizontal)h else w
            for(line in 0 until lines) {
                fun at(k: Int)=if(k in 0 until length)src[if(horizontal)line*w+k else k*w+line].toInt() and 255 else 0
                var sum=0;for(k in -r..r)sum+=at(k)
                for(k in 0 until length) {
                    out[if(horizontal)line*w+k else k*w+line]=(sum/(2*r+1)).toByte()
                    sum+=at(k+r+1)-at(k-r)
                }
            }
            return out
        }
        return pass(pass(input,true),false)
    }
    fun solve(state: JSONObject,source: Bitmap,p: JSONObject,area: Area,color: Int): Result {
        val rect=area.rect;val w=rect.width();val h=rect.height();val n=w*h
        val reference=IntArray(n);source.getPixels(reference,0,w,rect.left,rect.top,w,h)
        val outline=Region().apply {setPath(area.path,Region(rect))}
        val inside=ByteArray(n) {i->if(outline.contains(rect.left+i%w,rect.top+i/w))255.toByte() else 0}
        require(inside.any {it.toInt()!=0}) {"围合面积不足一个像素"}
        val coverage=IntArray(n)
        val shapeBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            Canvas(shapeBitmap).apply {translate(-rect.left.toFloat(),-rect.top.toFloat())
                drawPath(area.path,Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=Color.WHITE;style=Paint.Style.FILL})}
            shapeBitmap.getPixels(coverage,0,w,0,0,w,h)
        } finally {shapeBitmap.recycle()}
        val mode=p.getString("mode");val all=mode=="all";val threshold=p.getInt("tolerance")*255/100
        val wanted=Color.parseColor(p.getString("regionColor"))
        val candidate=ByteArray(n) {i->
            val transparent=Color.alpha(reference[i])<=threshold;val same=difference(reference[i],wanted)<=threshold
            val match=when(mode) {
                "all"->true;"transparent"->transparent;"color"->same;"color_or_transparent"->same||transparent
                "not_color"->!same;"not_transparent"->!transparent;else->!same&&!transparent
            }
            if(inside[i].toInt()!=0 && match)255.toByte() else 0
        }
        val gap=p.getInt("gapClose")
        // Opening disconnects narrow candidate corridors; selected cores are then expanded into the original candidate mask.
        val core=if(gap==0)candidate else morph(morph(candidate,w,h,gap,false),w,h,gap,true)
        val interior=morph(inside,w,h,gap+1,false)
        val visited=BooleanArray(n);val queue=IntArray(n);var selected=ByteArray(n);var regions=0
        for(seed in 0 until n) {
            if(core[seed].toInt()==0 || visited[seed])continue
            var head=0;var tail=1;queue[0]=seed;visited[seed]=true;var contour=false
            while(head<tail) {
                val i=queue[head++];if(interior[i].toInt()==0)contour=true
                fun visit(j: Int) {
                    if(!visited[j] && core[j].toInt()!=0 && (!all || difference(reference[j],reference[seed])<=threshold)) {
                        visited[j]=true;queue[tail++]=j
                    }
                }
                val x=i%w;val y=i/w
                if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
            }
            if(p.getBoolean("includeContour") || !contour) {
                regions++;for(k in 0 until tail)selected[queue[k]]=255.toByte()
            }
        }
        if(gap>0) {
            selected=morph(selected,w,h,gap,true)
            for(i in 0 until n)if(candidate[i].toInt()==0)selected[i]=0
        }
        if(p.getBoolean("invert"))for(i in 0 until n)selected[i]=if(inside[i].toInt()!=0 && selected[i].toInt()==0)255.toByte() else 0
        val expand=p.getInt("expand")
        if(expand!=0)selected=morph(selected,w,h,abs(expand),expand>0)
        selected=blur(selected,w,h,p.getInt("feather"))
        val selection=state.optJSONObject("selection")?.let {s->Region().apply {
            setPath(ArtSelection.path(s),Region(0,0,state.getInt("width"),state.getInt("height")))
        }}
        val output=IntArray(n);var filled=0
        val erase=p.getBoolean("erase");val opacity=p.getDouble("opacity")
        for(i in 0 until n) {
            if(inside[i].toInt()==0 || (selection!=null && !selection.contains(rect.left+i%w,rect.top+i/w)))continue
            val alpha=((selected[i].toInt() and 255)*Color.alpha(coverage[i])/255.0*opacity*
                (if(erase)1.0 else Color.alpha(color)/255.0)).roundToInt().coerceIn(0,255)
            if(alpha>0) {filled++;output[i]=Color.argb(alpha,if(erase)255 else Color.red(color),
                if(erase)255 else Color.green(color),if(erase)255 else Color.blue(color))}
        }
        val bitmap=Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888)
        try {state.optJSONObject("selection")?.takeIf {it.has("coverage")}?.let {ArtSoftSelection.maskBitmap(bitmap,it,rect.left,rect.top)}
            return Result(bitmap,filled,regions)
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }
}
