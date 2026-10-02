package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Original local PatchMatch implementation. No model, native library or Krita runtime. */
internal object ArtSmartPatch {
    const val MAX_POINTS = 4096
    const val MAX_MASK_PIXELS = 1048576
    const val MAX_REGION_PIXELS = 8388608
    const val MAX_WORK = ArtPatchPyramid.MAX_WORK
    val pending = listOf("跨图层取样", "变换或分组图层", "HDR 高位深")
    data class Options(val width: Float, val radius: Int, val accuracy: Int, val search: Int, val feather: Int, val levels: Int, val refinementStep: Int, val seed: Int)
    data class Mask(val left: Int, val top: Int, val width: Int, val height: Int,
        val selected: BooleanArray, val options: Options)
    data class Result(val patch: Bitmap, val erase: Bitmap, val pixels: Int, val work: Long, val levels: List<ArtPatchPyramid.Level>)
    // Pyramid images, two adjacent fields, integral/wavefront data, voting and output bitmaps coexist.
    fun workingBytes(w:Int,h:Int):Long=w.toLong()*h*96+(w.toLong()+1)*(h+1)*4
    fun levelReports(levels:List<ArtPatchPyramid.Level>)=JSONArray(levels.map {level->JSONObject()
        .put("width",level.width).put("height",level.height).put("scale",level.scale).put("maskPixels",level.maskPixels)
        .put("refinementStep",level.refinementStep).put("refinementCenters",level.centers)})
    fun info(): JSONObject = JSONObject().put("algorithm","multiscale-patchmatch")
        .put("coordinateSpace","document").put("target","current untransformed visible root paint/image layer")
        .put("maxPoints",MAX_POINTS).put("maxMaskPixels",MAX_MASK_PIXELS)
        .put("maxRegionPixels",MAX_REGION_PIXELS).put("maxComparisonsAndVotes",MAX_WORK).put("maxComparisons",MAX_WORK)
        .put("maxLevels",ArtPatchPyramid.MAX_LEVELS).put("nativeOutput",true)
        .put("localBytesPerPixel",100).put("workingBudgetBytes",ArtImagePolicy.budgetBytes())
        .put("planning","levels=0 plans depth from mask and clean donor geometry; refinementStep=0 sets each level spacing from area and accuracy. Large repairs use sparse refinement and dense native donor voting. Budget failures do not write assets or history.")
        .put("pending",JSONArray(pending)).put("ranges",JSONObject()
            .put("width",JSONArray().put(1).put(256)).put("patchRadius",JSONArray().put(1).put(8))
            .put("accuracy",JSONArray().put(1).put(100)).put("searchRadius",JSONArray().put(16).put(1024))
            .put("feather",JSONArray().put(0).put(8)).put("levels",JSONArray().put(0).put(6))
            .put("refinementStep",JSONArray().put(0).put(64)).put("seed",JSONArray().put(0).put(Int.MAX_VALUE)))
        .put("defaults",JSONObject().put("width",32)
            .put("patchRadius",4).put("accuracy",40).put("searchRadius",64).put("feather",2)
            .put("levels",0).put("refinementStep",0).put("seed",0))
    fun options(p: JSONObject): Options {
        fun integer(key: String, default: Int, range: IntRange): Int {
            val value=if(p.has(key))p.get(key) else default
            // Braces stop the adjacent Chinese diagnostic from becoming part of the Kotlin identifier.
            require(value is Number) {"${key}需要整数"};val n=value.toDouble()
            require(n.isFinite() && n==floor(n) && n>=range.first && n<=range.last) { "$key 超出范围" }
            return n.toInt()
        }
        val width=p.getDouble("width")
        require(width.isFinite() && width in 1.0..256.0) { "修补笔径需要在1–256像素之间" }
        return Options(width.toFloat(),integer("patchRadius",4,1..8),integer("accuracy",40,1..100),
            integer("searchRadius",64,16..1024),integer("feather",2,0..8),integer("levels",0,0..6),
            integer("refinementStep",0,0..64),integer("seed",0,0..Int.MAX_VALUE))
    }
    fun points(p: JSONObject): List<Pair<Float,Float>> {
        val input=p.getJSONArray("points")
        require(input.length() in 1..MAX_POINTS) { "修补路径需要1–4096个坐标" }
        return (0 until input.length()).map {
            val point=input.getJSONArray(it);require(point.length()==2) { "修补坐标需要二维数组" }
            val x=point.getDouble(0);val y=point.getDouble(1)
            require(x.isFinite() && y.isFinite() && abs(x)<=1000000 && abs(y)<=1000000) { "修补坐标无效" }
            x.toFloat() to y.toFloat()
        }
    }
    fun path(points: List<Pair<Float,Float>>): Path = Path().apply {
        points.forEachIndexed { i,p -> if(i==0)moveTo(p.first,p.second) else lineTo(p.first,p.second) }
    }
    fun drawMask(canvas: Canvas, points: List<Pair<Float,Float>>, width: Float, color: Int) {
        if(points.isEmpty())return
        val paint=Paint().apply {
            this.color=color;strokeWidth=width;style=Paint.Style.STROKE
            strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
        }
        if(points.size>1)canvas.drawPath(path(points),paint)
        paint.style=Paint.Style.FILL
        // A dot is a real mask, including a path whose samples coincide.
        canvas.drawCircle(points.first().first,points.first().second,width/2,paint)
        canvas.drawCircle(points.last().first,points.last().second,width/2,paint)
    }
    fun mask(state: JSONObject,p: JSONObject): Mask {
        val options=options(p);val points=points(p)
        val cw=state.getInt("width");val ch=state.getInt("height")
        val half=options.width/2
        val ml=floor(points.minOf { it.first }-half).toInt().coerceIn(0,cw)
        val mt=floor(points.minOf { it.second }-half).toInt().coerceIn(0,ch)
        val mr=ceil(points.maxOf { it.first }+half).toInt().coerceIn(0,cw)
        val mb=ceil(points.maxOf { it.second }+half).toInt().coerceIn(0,ch)
        require(mr>ml && mb>mt) { "修补区域不在画布内" }
        val pad=options.search+options.radius
        val left=(ml-pad).coerceAtLeast(0);val top=(mt-pad).coerceAtLeast(0)
        val right=(mr+pad).coerceAtMost(cw);val bottom=(mb+pad).coerceAtMost(ch)
        val w=right-left;val h=bottom-top
        require(w.toLong()*h<=MAX_REGION_PIXELS) { "修补搜索外框最多8388608像素，请缩小搜索半径或涂抹范围" }
        ArtImagePolicy.requireBytes(workingBytes(w,h),"智能修补局部数据")
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val selected=BooleanArray(w*h)
        try {
            val c=Canvas(bitmap);c.translate(-left.toFloat(),-top.toFloat())
            drawMask(c,points,options.width,Color.WHITE)
            val selection=state.optJSONObject("selection")?.let {
                Region().apply { setPath(ArtSelection.path(it),Region(0,0,cw,ch)) }
            }
            val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
            var count=0
            for(i in pixels.indices) if(Color.alpha(pixels[i])>=128 &&
                (selection==null || selection.contains(left+i%w,top+i/w))) {
                selected[i]=true;count++
            }
            require(count>0) { "涂抹区域没有覆盖当前选区或画布" }
            require(count<=MAX_MASK_PIXELS) { "单次修补最多1048576个涂抹像素，请缩小笔径或范围" }
        } finally {bitmap.recycle()}
        return Mask(left,top,w,h,selected,options)
    }
    fun repair(source: Bitmap,mask: Mask,selection:JSONObject?=null): Result {
        val coverage=selection?.takeIf {it.has("coverage")}?.let {ArtSoftSelection.Sampler(it)}
        val w=mask.width;val h=mask.height;val size=w*h;val hole=mask.selected;val o=mask.options
        val original=IntArray(size);source.getPixels(original,0,w,mask.left,mask.top,w,h)
        ArtImagePolicy.requireBytes(workingBytes(w,h),"多尺度智能修补")
        val repaired=ArtPatchPyramid.repair(original,hole,w,h,o.radius,o.search,o.accuracy,o.levels,o.refinementStep,o.seed)
        val holes=IntArray(hole.count {it});var index=0
        for(i in hole.indices)if(hole[i])holes[index++]=i
        val queue=IntArray(size);var head=0;var tail=0
        val depth=IntArray(size) { -1 };head=0;tail=0
        for(i in holes) {
            val x=i%w;val y=i/w
            if(x==0 || y==0 || x==w-1 || y==h-1 ||
                !hole[i-1] || !hole[i+1] || !hole[i-w] || !hole[i+w]) {
                depth[i]=1;queue[tail++]=i
            }
        }
        while(head<tail) {
            val i=queue[head++];val x=i%w;val y=i/w
            fun visit(n: Int) { if(hole[n] && depth[n]<0) { depth[n]=depth[i]+1;queue[tail++]=n } }
            if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
        }
        val output=IntArray(size);val erasure=IntArray(size)
        for(i in holes) {
            val mix=if(o.feather==0)1.0 else (depth[i].toDouble()/(o.feather+1)).coerceAtMost(1.0)
            output[i]=ArtSoftSelection.blend(original[i],repaired.pixels[i],(mix*255).roundToInt().coerceIn(0,255))
            val amount=coverage?.at(mask.left+i%w+0.5,mask.top+i/w+0.5) ?: 255
            if(amount==0)output[i]=0 else {
                output[i]=ArtSoftSelection.blend(original[i],output[i],amount)
                erasure[i]=Color.WHITE
            }
        }
        val patch=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            patch.setPixels(output,0,w,0,0,w,h)
            val erase=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {erase.setPixels(erasure,0,w,0,0,w,h)
                return Result(patch,erase,holes.size,repaired.work,repaired.levels)}
            catch(error:Throwable) {erase.recycle();throw error}
        } catch(error:Throwable) {patch.recycle();throw error}
    }
}
