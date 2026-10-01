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
import java.util.Random

/** Original local PatchMatch implementation. No model, native library or Krita runtime. */
internal object ArtSmartPatch {
    const val MAX_POINTS = 4096
    const val MAX_MASK_PIXELS = 32768
    const val MAX_REGION_PIXELS = 1048576
    const val MAX_WORK = 80000000L
    val pending = listOf("跨图层取样", "变换或分组图层", "大面积多尺度修补", "HDR 高位深")
    data class Options(val width: Float, val radius: Int, val accuracy: Int, val search: Int, val feather: Int)
    data class Mask(val left: Int, val top: Int, val width: Int, val height: Int,
        val selected: BooleanArray, val options: Options)
    data class Result(val patch: Bitmap, val erase: Bitmap, val pixels: Int, val work: Long)
    fun info(): JSONObject = JSONObject().put("algorithm","local-patchmatch")
        .put("coordinateSpace","document").put("target","current untransformed visible root paint/image layer")
        .put("maxPoints",MAX_POINTS).put("maxMaskPixels",MAX_MASK_PIXELS)
        .put("maxRegionPixels",MAX_REGION_PIXELS).put("maxComparisons",MAX_WORK)
        .put("pending",JSONArray(pending)).put("ranges",JSONObject()
            .put("width",JSONArray().put(1).put(256)).put("patchRadius",JSONArray().put(1).put(8))
            .put("accuracy",JSONArray().put(1).put(100)).put("searchRadius",JSONArray().put(16).put(256))
            .put("feather",JSONArray().put(0).put(8)))
        .put("defaults",JSONObject().put("width",32)
            .put("patchRadius",4).put("accuracy",40).put("searchRadius",64).put("feather",2))
    fun options(p: JSONObject): Options {
        fun integer(key: String, default: Int, range: IntRange): Int {
            val n=if(p.has(key))p.getDouble(key) else default.toDouble()
            require(n.isFinite() && n==floor(n) && n>=range.first && n<=range.last) { "$key 超出范围" }
            return n.toInt()
        }
        val width=p.getDouble("width")
        require(width.isFinite() && width in 1.0..256.0) { "修补笔径需要在1–256像素之间" }
        return Options(width.toFloat(),integer("patchRadius",4,1..8),integer("accuracy",40,1..100),
            integer("searchRadius",64,16..256),integer("feather",2,0..8))
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
        require(w.toLong()*h<=MAX_REGION_PIXELS) { "修补搜索区域过大，请分笔修补或缩小搜索范围" }
        ArtImagePolicy.requireBytes(w.toLong()*h*64,"智能修补局部数据")
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
            require(count<=MAX_MASK_PIXELS) { "单次修补最多32768像素，请缩小笔径或分笔修补" }
        } finally {bitmap.recycle()}
        return Mask(left,top,w,h,selected,options)
    }
    fun repair(source: Bitmap,mask: Mask): Result {
        val w=mask.width;val h=mask.height;val size=w*h;val hole=mask.selected;val o=mask.options
        val original=IntArray(size);source.getPixels(original,0,w,mask.left,mask.top,w,h)
        val target=original.copyOf();val field=IntArray(size) { -1 }
        val integral=IntArray((w+1)*(h+1))
        for(y in 0 until h) {
            var row=0
            for(x in 0 until w) {
                if(hole[y*w+x])row++
                integral[(y+1)*(w+1)+x+1]=integral[y*(w+1)+x+1]+row
            }
        }
        fun maskedBox(x: Int,y: Int): Int {
            val r=o.radius;val stride=w+1;val x0=x-r;val y0=y-r;val x1=x+r+1;val y1=y+r+1
            return integral[y1*stride+x1]-integral[y0*stride+x1]-integral[y1*stride+x0]+integral[y0*stride+x0]
        }
        val valid=BooleanArray(size)
        val nearest=IntArray(size) { -1 };val queue=IntArray(size);var tail=0;var head=0
        for(y in o.radius until h-o.radius) for(x in o.radius until w-o.radius) {
            val i=y*w+x
            if(maskedBox(x,y)==0 && Color.alpha(original[i])>0) {
                valid[i]=true;nearest[i]=i;queue[tail++]=i
            }
        }
        require(tail>0) { "附近没有未被涂抹的有效纹理，请缩小修补区域或补丁半径" }
        // Multi-source wavefront gives a defined initialization for every unknown pixel.
        while(head<tail) {
            val i=queue[head++];val x=i%w;val y=i/w
            fun visit(n: Int) { if(nearest[n]<0) { nearest[n]=nearest[i];queue[tail++]=n } }
            if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
            if(x>0 && y>0)visit(i-w-1);if(x+1<w && y>0)visit(i-w+1)
            if(x>0 && y+1<h)visit(i+w-1);if(x+1<w && y+1<h)visit(i+w+1)
        }
        val holes=(0 until size).filter { hole[it] }.toIntArray()
        fun inSearch(i: Int,c: Int): Boolean = valid[c] &&
            abs(i%w-c%w)<=o.search && abs(i/w-c/w)<=o.search
        for(i in holes) {
            val c=nearest[i]
            require(inSearch(i,c)) { "部分修补像素附近没有可用补丁，请扩大搜索范围或分笔修补" }
            field[i]=c;target[i]=original[c]
        }
        var work=0L
        val offsets=(-o.radius..o.radius).filter { it==-o.radius || it==o.radius || it==0 || abs(it)==max(1,o.radius/2) }
        fun channelDiff(a: Int,b: Int): Double {
            val aa=Color.alpha(a)/255.0;val ba=Color.alpha(b)/255.0
            val dr=Color.red(a)*aa-Color.red(b)*ba
            val dg=Color.green(a)*aa-Color.green(b)*ba
            val db=Color.blue(a)*aa-Color.blue(b)*ba
            val da=Color.alpha(a)-Color.alpha(b)
            return dr*dr+dg*dg+db*db+da.toDouble()*da
        }
        fun score(i: Int,c: Int,limit: Double): Double {
            if(c !in 0 until size || !inSearch(i,c))return Double.POSITIVE_INFINITY
            val x=i%w;val y=i/w;val cx=c%w;val cy=c/w;var error=0.0
            for(dy in offsets)for(dx in offsets) {
                if(x+dx !in 0 until w || y+dy !in 0 until h)continue
                if(++work>MAX_WORK)kotlin.error("智能修补计算量过大，请缩小区域、搜索范围或精度")
                val t=(y+dy)*w+x+dx;val s=(cy+dy)*w+cx+dx
                error+=channelDiff(target[t],original[s])*(if(hole[t])0.25 else 1.0)
                if(error>=limit)return error
            }
            return error
        }
        val random=Random(0x534d415254L)
        val passes=2+(o.accuracy-1)*6/99
        for(pass in 0 until passes) {
            val forward=pass%2==0
            for(k in holes.indices) {
                if(Thread.currentThread().isInterrupted)error("智能修补已取消")
                val i=holes[if(forward)k else holes.lastIndex-k];val x=i%w;val y=i/w
                var best=field[i];var distance=score(i,best,Double.POSITIVE_INFINITY)
                fun tryCandidate(c: Int) {
                    val d=score(i,c,distance)
                    if(d<distance) { distance=d;best=c }
                }
                val side=if(forward)-1 else 1
                if(x+side in 0 until w) {
                    val n=i+side
                    if(hole[n] && field[n]>=0) {
                        val sx=field[n]%w-side;val sy=field[n]/w
                        if(sx in 0 until w)tryCandidate(sy*w+sx)
                    }
                }
                if(y+side in 0 until h) {
                    val n=i+side*w
                    if(hole[n] && field[n]>=0) {
                        val sx=field[n]%w;val sy=field[n]/w-side
                        if(sy in 0 until h)tryCandidate(sy*w+sx)
                    }
                }
                var range=o.search
                while(range>=1) {
                    repeat(1+o.accuracy/34) {
                        val cx=(best%w+random.nextInt(range*2+1)-range).coerceIn(o.radius,w-o.radius-1)
                        val cy=(best/w+random.nextInt(range*2+1)-range).coerceIn(o.radius,h-o.radius-1)
                        tryCandidate(cy*w+cx)
                    }
                    range/=2
                }
                field[i]=best;target[i]=original[best]
            }
        }
        // Vote complete donor patches, then blend only inside the painted mask.
        // This reduces seams between correspondence offsets without changing known pixels.
        val sums=Array(4) { FloatArray(size) };val weights=FloatArray(size)
        for(i in holes) {
            val sx=field[i]%w;val sy=field[i]/w;val x=i%w;val y=i/w
            for(dy in -o.radius..o.radius)for(dx in -o.radius..o.radius) {
                val tx=x+dx;val ty=y+dy
                if(tx !in 0 until w || ty !in 0 until h)continue
                val t=ty*w+tx;if(!hole[t])continue
                val color=original[(sy+dy)*w+sx+dx]
                val weight=1f/(1+dx*dx+dy*dy);val alpha=Color.alpha(color)/255f
                weights[t]+=weight;sums[0][t]+=alpha*weight
                sums[1][t]+=Color.red(color)*alpha*weight
                sums[2][t]+=Color.green(color)*alpha*weight
                sums[3][t]+=Color.blue(color)*alpha*weight
            }
        }
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
            val a=sums[0][i]/weights[i];val oldAlpha=Color.alpha(original[i])/255.0
            val alpha=a*mix+oldAlpha*(1-mix)
            fun channel(sum: Float,old: Int): Int = if(alpha==0.0)0 else
                ((sum/weights[i]*mix+old*oldAlpha*(1-mix))/alpha).roundToInt().coerceIn(0,255)
            output[i]=Color.argb((alpha*255).roundToInt().coerceIn(0,255),
                channel(sums[1][i],Color.red(original[i])),channel(sums[2][i],Color.green(original[i])),
                channel(sums[3][i],Color.blue(original[i])))
            erasure[i]=Color.WHITE
        }
        val patch=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            patch.setPixels(output,0,w,0,0,w,h)
            val erase=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {erase.setPixels(erasure,0,w,0,0,w,h);return Result(patch,erase,holes.size,work)}
            catch(error:Throwable) {erase.recycle();throw error}
        } catch(error:Throwable) {patch.recycle();throw error}
    }
}
