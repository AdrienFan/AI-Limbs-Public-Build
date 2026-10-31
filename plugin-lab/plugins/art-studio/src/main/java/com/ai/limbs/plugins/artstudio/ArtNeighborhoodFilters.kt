package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*

/** Linear, premultiplied RGBA input. No Android objects, document edits or resource writes. */
internal object ArtNeighborhoodFilters {
    val ids = setOf("filter.gaussian_blur", "filter.blur", "filter.motion_blur", "filter.lens_blur",
        "filter.sharpen", "filter.mean_removal", "filter.unsharp_mask", "filter.gaussian_high_pass",
        "filter.top_edge_detection", "filter.emboss_laplacian", "filter.emboss_in_all_directions",
        "filter.emboss_horizontal_vertical", "filter.emboss_vertical_only", "filter.emboss_horizontal_only",
        "filter.emboss_with_variable_depth")
    private const val MAX_WORK = 250_000_000L
    private fun cancelled() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("滤镜已取消") }
    private fun number(p: JSONObject, key: String, default: Double, min: Double, max: Double): Double {
        val v = if (p.has(key)) p.getDouble(key) else default
        require(v.isFinite() && v in min..max) { "$key 需要有限值 $min–$max" }
        return v
    }
    private fun choice(p: JSONObject, key: String, default: String, values: Set<String>): String =
        p.optString(key, default).also { require(it in values) { "$key 选项无效" } }
    private fun work(w: Int, h: Int, taps: Int) {
        require(w.toLong() * h * taps <= MAX_WORK) { "滤镜运算量超过单次预算，请减小半径或画布" }
    }
    private fun straight(v: FloatArray, i: Int, c: Int): Float = if (v[i+3] > 0f) v[i+c]/v[i+3] else 0f

    fun process(action: String, source: FloatArray, w: Int, h: Int, p: JSONObject,
        budgetBytes: Long = 256L*1024*1024): FloatArray {
        require(action in ids) { "未知邻域滤镜" }
        require(w > 0 && h > 0 && w.toLong()*h*4 == source.size.toLong())
        // Input, output and a separable-pass buffer coexist; include four bitmap/encoding buffers.
        require(w.toLong()*h*80 <= budgetBytes) { "滤镜工作内存超过预算" }
        cancelled()
        source.forEachIndexed { i, v -> require(v.isFinite() && if(i%4==3) v in 0f..1f else abs(v)<=65504f) }
        val filtered = when(action) {
            "filter.gaussian_blur" -> gaussian(source,w,h,
                number(p,"sigmaX",3.0,0.0,128.0),number(p,"sigmaY",3.0,0.0,128.0))
            "filter.blur" -> {
                val rx=number(p,"radiusX",5.0,0.0,32.0);val ry=number(p,"radiusY",5.0,0.0,32.0)
                val angle=number(p,"angle",0.0,-360.0,360.0)*PI/180
                val rectangle=choice(p,"shape","ellipse",setOf("ellipse","rectangle"))=="rectangle"
                val extent=ceil(hypot(rx,ry)).toInt()
                kernel(source,w,h,extent) {x,y ->
                    val u=x*cos(angle)+y*sin(angle);val v=-x*sin(angle)+y*cos(angle)
                    val ux=if(rx==0.0) if(abs(u)<0.5)0.0 else Double.POSITIVE_INFINITY else u/rx
                    val vy=if(ry==0.0) if(abs(v)<0.5)0.0 else Double.POSITIVE_INFINITY else v/ry
                    if(if(rectangle) abs(ux)<=1 && abs(vy)<=1 else ux*ux+vy*vy<=1) 1.0 else 0.0
                }
            }
            "filter.lens_blur" -> {
                val radius=number(p,"radius",5.0,0.0,32.0)
                val sides=number(p,"sides",5.0,3.0,12.0).also {require(it==floor(it))}.toInt()
                val rotation=number(p,"angle",0.0,-360.0,360.0)*PI/180
                kernel(source,w,h,ceil(radius).toInt()) {x,y ->
                    val theta=atan2(y.toDouble(),x.toDouble())-rotation
                    val sector=2*PI/sides;val folded=((theta+PI/sides)%sector+sector)%sector-PI/sides
                    if(hypot(x.toDouble(),y.toDouble())<=radius*cos(PI/sides)/cos(folded))1.0 else 0.0
                }
            }
            "filter.motion_blur" -> {
                val distance=number(p,"distance",10.0,0.0,128.0)
                val angle=number(p,"angle",0.0,-360.0,360.0)*PI/180
                val taps=ceil(distance).toInt()+1;work(w,h,taps)
                val out=FloatArray(source.size)
                for(y in 0 until h) {cancelled();for(x in 0 until w)for(t in 0 until taps) {
                    val d=if(taps==1)0.0 else distance*(t.toDouble()/(taps-1)-0.5)
                    val sx=(x+d*cos(angle)).coerceIn(0.0,w-1.0);val sy=(y+d*sin(angle)).coerceIn(0.0,h-1.0)
                    val ix=sx.toInt();val iy=sy.toInt();val fx=(sx-ix).toFloat();val fy=(sy-iy).toFloat()
                    for(c in 0..3) {
                        val a=source[(iy*w+ix)*4+c]*(1-fx)+source[(iy*w+minOf(w-1,ix+1))*4+c]*fx
                        val b=source[(minOf(h-1,iy+1)*w+ix)*4+c]*(1-fx)+source[(minOf(h-1,iy+1)*w+minOf(w-1,ix+1))*4+c]*fx
                        out[(y*w+x)*4+c]+=(a*(1-fy)+b*fy)/taps
                    }
                }}
                out
            }
            "filter.unsharp_mask", "filter.gaussian_high_pass" -> {
                val sigma=number(p,"sigma",3.0,0.0,128.0)
                val blurred=gaussian(source,w,h,sigma,sigma)
                val amount=if(action=="filter.unsharp_mask")number(p,"amount",1.0,0.0,10.0).toFloat() else 1f
                val threshold=if(action=="filter.unsharp_mask")number(p,"threshold",0.0,0.0,1.0).toFloat() else 0f
                val lightnessOnly=action=="filter.unsharp_mask" && p.optBoolean("lightnessOnly",true)
                FloatArray(source.size).also {out -> for(i in source.indices step 4) {
                    if(i%16384==0)cancelled()
                    val alpha=source[i+3];out[i+3]=alpha
                    val lumaDelta=if(lightnessOnly) {
                        (straight(source,i,0)-straight(blurred,i,0))*.2126f+
                            (straight(source,i,1)-straight(blurred,i,1))*.7152f+
                            (straight(source,i,2)-straight(blurred,i,2))*.0722f
                    } else 0f
                    for(c in 0..2) {
                        val original=straight(source,i,c)
                        // Linear-luminance mode adds the same delta to RGB, retaining channel differences.
                        val delta=if(lightnessOnly)lumaDelta else original-straight(blurred,i,c)
                        val v=if(action=="filter.gaussian_high_pass")delta+0.5f
                            else original+if(abs(delta)>=threshold)amount*delta else 0f
                        out[i+c]=if(alpha==0f)source[i+c] else v*alpha
                    }
                }}
            }
            "filter.emboss_with_variable_depth" -> {
                // Diagonal difference and grayscale follow the reference operation, in our linear HDR space.
                val depth=floor(number(p,"depth",30.0,10.0,300.0)/10).toFloat()
                work(w,h,2)
                FloatArray(source.size).also {out -> for(y in 0 until h) {cancelled();for(x in 0 until w) {
                    val i=(y*w+x)*4;val j=(minOf(y+1,h-1)*w+minOf(x+1,w-1))*4
                    var gray=0f
                    for(c in 0..2)gray+=abs((straight(source,i,c)-straight(source,j,c))*depth+0.5f)/3
                    for(c in 0..2)out[i+c]=if(source[i+3]==0f)source[i+c] else gray*source[i+3]
                    out[i+3]=source[i+3]
                }}}
            }
            else -> {
                val k: FloatArray;val factor:Float;val offset:Float
                when(action) {
                    "filter.sharpen" -> {k=floatArrayOf(0f,-2f,0f,-2f,11f,-2f,0f,-2f,0f);factor=3f;offset=0f}
                    "filter.mean_removal" -> {k=floatArrayOf(-1f,-1f,-1f,-1f,9f,-1f,-1f,-1f,-1f);factor=1f;offset=0f}
                    "filter.emboss_in_all_directions" -> {k=floatArrayOf(-1f,-1f,-1f,-1f,8f,-1f,-1f,-1f,-1f);factor=1f;offset=.5f}
                    "filter.emboss_horizontal_vertical" -> {k=floatArrayOf(0f,-1f,0f,-1f,4f,-1f,0f,-1f,0f);factor=1f;offset=.5f}
                    "filter.emboss_vertical_only" -> {k=floatArrayOf(0f,-1f,0f,0f,2f,0f,0f,-1f,0f);factor=1f;offset=.5f}
                    "filter.emboss_horizontal_only" -> {k=floatArrayOf(0f,0f,0f,-1f,2f,-1f,0f,0f,0f);factor=1f;offset=.5f}
                    else -> {k=floatArrayOf(-1f,0f,-1f,0f,4f,0f,-1f,0f,-1f);factor=1f;offset=.5f}
                }
                work(w,h,9)
                val out=FloatArray(source.size)
                for(y in 0 until h) {cancelled();for(x in 0 until w) {
                    val i=(y*w+x)*4;val alpha=source[i+3];out[i+3]=alpha
                    for(c in 0..2) {
                        var sum=0.0
                        for(dy in -1..1)for(dx in -1..1)sum+=straight(source,((y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1))*4,c)*k[(dy+1)*3+dx+1]
                        out[i+c]=if(alpha==0f)source[i+c] else (sum/factor+offset).toFloat()*alpha
                    }
                }}
                out
            }
        }
        val includeAlpha=action in setOf("filter.gaussian_blur","filter.blur","filter.motion_blur","filter.lens_blur")
        val channels=booleanArrayOf(p.optBoolean("red",true),p.optBoolean("green",true),p.optBoolean("blue",true),p.optBoolean("alpha",includeAlpha))
        require(channels.any {it}) {"至少选择一个通道"}
        for(i in source.indices step 4) {
            if(i%16384==0)cancelled()
            val oldAlpha=source[i+3];val filteredAlpha=filtered[i+3].coerceIn(0f,1f)
            val alpha=if(channels[3])filteredAlpha else oldAlpha
            for(c in 0..2) {
                val value=if(channels[c]) {
                    if(filteredAlpha==alpha)filtered[i+c] else if(filteredAlpha>0)filtered[i+c]/filteredAlpha*alpha else 0f
                } else if(oldAlpha==alpha)source[i+c] else if(oldAlpha>0)source[i+c]/oldAlpha*alpha else 0f
                require(value.isFinite() && abs(value)<=65504f) {"滤镜结果超出浮点图像范围"}
                filtered[i+c]=value
            }
            filtered[i+3]=alpha
        }
        return filtered
    }

    private fun gaussian(src:FloatArray,w:Int,h:Int,sx:Double,sy:Double):FloatArray {
        fun weights(sigma:Double):DoubleArray {
            if(sigma==0.0)return doubleArrayOf(1.0)
            val r=ceil(3*sigma).toInt();val a=DoubleArray(2*r+1) {exp(-(it-r).toDouble().pow(2)/(2*sigma*sigma))}
            val sum=a.sum();return DoubleArray(a.size) {a[it]/sum}
        }
        val x=weights(sx);val y=weights(sy);work(w,h,x.size+y.size)
        fun pass(input:FloatArray,k:DoubleArray,horizontal:Boolean):FloatArray {
            val out=FloatArray(input.size);val radius=k.size/2
            for(row in 0 until h) {cancelled();for(col in 0 until w)for(c in 0..3) {
                var sum=0.0
                for(t in k.indices) {
                    val px=if(horizontal)(col+t-radius).coerceIn(0,w-1) else col
                    val py=if(horizontal)row else (row+t-radius).coerceIn(0,h-1)
                    sum+=input[(py*w+px)*4+c]*k[t]
                }
                out[(row*w+col)*4+c]=sum.toFloat()
            }}
            return out
        }
        return pass(pass(src,x,true),y,false)
    }

    private fun kernel(src:FloatArray,w:Int,h:Int,r:Int,weight:(Int,Int)->Double):FloatArray {
        val taps=mutableListOf<Triple<Int,Int,Double>>()
        for(y in -r..r)for(x in -r..r) {val v=weight(x,y);if(v>0)taps.add(Triple(x,y,v))}
        require(taps.isNotEmpty());work(w,h,taps.size)
        val norm=taps.sumOf {it.third};val out=FloatArray(src.size)
        for(y in 0 until h) {cancelled();for(x in 0 until w)for(c in 0..3) {
            var sum=0.0
            for((dx,dy,v) in taps)sum+=src[((y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1))*4+c]*v
            out[(y*w+x)*4+c]=(sum/norm).toFloat()
        }}
        return out
    }
}
