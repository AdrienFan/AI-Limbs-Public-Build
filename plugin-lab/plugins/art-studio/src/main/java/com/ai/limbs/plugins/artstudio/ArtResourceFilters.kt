package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.cancelled
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.number
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.integer
import com.ai.limbs.plugins.artstudio.ArtFilterRaster.Companion.choice

/** Resolved value resources are immutable parameters, never live workspace dependencies during replay. */
internal object ArtResourceFilters {
    val ids=setOf("filter.halftone","filter.index_colors","filter.propagate_colors","filter.color_transfer","filter.palettize")
    data class Result(val pixels:FloatArray,val hidden:FloatArray?)
    private val bayer=intArrayOf(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5)
    private fun fields(o:JSONObject,allowed:Set<String>) {require(o.keys().asSequence().all {it in allowed}) {"资源参数含未知字段"}}
    fun color(v:String):DoubleArray {
        require(v.matches(Regex("#[0-9a-fA-F]{8}"))) {"资源颜色需要#AARRGGBB"}
        val bits=v.drop(1).toLong(16)
        return DoubleArray(4) {if(it==3)((bits shr 24) and 255)/255.0 else ArtFilterColorSpace.decode(((bits shr (16-it*8)) and 255)/255.0)}
    }
    private fun vector(p:JSONObject,key:String,default:DoubleArray,min:Double,max:Double):DoubleArray {
        if(!p.has(key))return default.copyOf()
        val a=p.getJSONArray(key);require(a.length()==3)
        return DoubleArray(3) {val v=a.get(it);require(v is Number);v.toDouble().also {require(it.isFinite() && it in min..max)}}
    }
    data class Statistics(val mean:DoubleArray,val std:DoubleArray,val weight:Double) {
        fun json()=JSONObject().put("model","lab-d65").put("mean",JSONArray(mean.toList())).put("std",JSONArray(std.toList()))
    }
    fun statistics(source:FloatArray):Statistics {
        require(source.size%4==0)
        val mean=DoubleArray(3);val m2=DoubleArray(3);var weight=0.0
        for(i in source.indices step 4) {
            if(i%16384==0)cancelled()
            val a=source[i+3].toDouble()
            require(a.isFinite() && a in 0.0..1.0)
            for(c in 0..2)require(source[i+c].isFinite() && abs(source[i+c])<=65504f)
            if(a==0.0)continue
            val lab=ArtFilterColorSpace.lab(DoubleArray(3) {source[i+it]/a})
            val next=weight+a
            for(c in 0..2) {
                require(lab[c].isFinite() && abs(lab[c])<=1e12) {"参考图层Lab统计超出支持范围"}
                val delta=lab[c]-mean[c];mean[c]+=delta*a/next;m2[c]+=a*delta*(lab[c]-mean[c])
            }
            weight=next
        }
        require(weight>0) {"参考或源图层没有非透明像素"}
        return Statistics(mean,DoubleArray(3) {sqrt(max(0.0,m2[it]/weight))},weight)
    }
    fun process(action:String,source:FloatArray,w:Int,h:Int,p:JSONObject,hidden:FloatArray?=null,
        budgetBytes:Long=256L*1024*1024):Result {
        require(action in ids);val r=ArtFilterRaster(source,w,h,budgetBytes)
        require(w.toLong()*h*128<=budgetBytes) {"资源滤镜工作内存超过预算"}
        if(hidden!=null)ArtHiddenColors.validatePixels(hidden,source.size)
        if(action=="filter.propagate_colors")return ArtColorPropagation.process(r,p,hidden)
        val out=source.copyOf();val hiddenOut=hidden?.copyOf() ?: FloatArray(source.size)
        when(action) {
            "filter.halftone" -> halftone(r,out,p)
            "filter.index_colors","filter.palettize" -> quantize(r,out,hiddenOut,p,action=="filter.index_colors")
            "filter.color_transfer" -> {
                r.work(32)
                val target=p.getJSONObject("referenceStats");fields(target,setOf("model","mean","std","provenance"))
                require(target.getString("model")=="lab-d65") {"参考统计必须使用lab-d65模型"}
                val mean=vector(target,"mean",doubleArrayOf(50.0,0.0,0.0),-1e12,1e12)
                val std=vector(target,"std",doubleArrayOf(15.0,10.0,10.0),0.0,1e12)
                require(target.has("mean") && target.has("std"))
                if(target.has("provenance"))target.getJSONObject("provenance").let {o->
                    fields(o,setOf("documentId","revision","layerId"));require(o.getString("documentId").length<=100 && o.getString("layerId").length<=100 && o.getInt("revision")>=0)
                }
                val stats=statistics(source);val amount=number(p,"amount",1.0,0.0,1.0)
                for(i in source.indices step 4) {
                    if(i%16384==0)cancelled();if(source[i+3]==0f)continue
                    val original=DoubleArray(3) {r.straight(i,it)};val lab=ArtFilterColorSpace.lab(original)
                    // A constant source axis has no variance to scale; its defined mapping is the target mean.
                    for(c in 0..2)lab[c]=mean[c]+(lab[c]-stats.mean[c])*(if(stats.std[c]==0.0)0.0 else std[c]/stats.std[c])
                    val mapped=ArtFilterColorSpace.fromLab(lab)
                    for(c in 0..2)out[i+c]=((original[c]*(1-amount)+mapped[c]*amount)*source[i+3]).toFloat()
                }
            }
        }
        r.finish(out,p,true)
        for(i in source.indices step 4) {
            if(i%16384==0)cancelled()
            if(out[i+3]>0)for(c in 0..3)hiddenOut[i+c]=0f
        }
        return Result(out,hiddenOut)
    }
    private fun palette(p:JSONObject,index:Boolean):List<DoubleArray> {
        if(!index) {
            val a=p.getJSONArray("palette");require(a.length() in 2..512) {"调色板需要2–512色"}
            return (0 until a.length()).map {color(a.getString(it))}
        }
        val ramps=p.getJSONArray("ramps");require(ramps.length() in 1..4)
        val steps=if(p.has("gradientSteps"))p.getJSONArray("gradientSteps") else JSONArray("[4,4,4]")
        require(steps.length()==3)
        val counts=IntArray(3) {val n=steps.get(it);require(n is Number && n.toDouble()==floor(n.toDouble()) && n.toInt() in 0..8);n.toInt()}
        val between=integer(p,"betweenSteps",2,0,8);val diagonal=p.optBoolean("diagonal",false)
        val grid=Array(ramps.length()) {x->val a=ramps.getJSONArray(x);require(a.length()==4)
            arrayOfNulls<DoubleArray>(4).also {row->for(y in 0..3)if(!a.isNull(y))row[y]=ArtFilterColorSpace.lab(color(a.getString(y)))}}
        val labs=mutableListOf<DoubleArray>()
        grid.forEach {row->row.filterNotNull().forEach {labs.add(it)}}
        fun shades(a:DoubleArray?,b:DoubleArray?,n:Int) {
            if(a==null || b==null)return
            for(i in 1..n) {val t=i.toDouble()/(n+1);labs.add(DoubleArray(3) {a[it]*(1-t)+b[it]*t})}
        }
        for(x in grid.indices)for(y in 0..2)shades(grid[x][y],grid[x][y+1],counts[y])
        for(x in 0 until grid.size-1)for(y in 0..3)shades(grid[x][y],grid[x+1][y],between)
        if(diagonal)for(x in 0 until grid.size-1)for(y in 0..2) {
            shades(grid[x][y],grid[x+1][y+1],counts[y]);shades(grid[x+1][y],grid[x][y+1],counts[y])
        }
        val unique=labs.distinctBy {it.toList()}.toMutableList();require(unique.size in 2..512) {"索引色坡需要2–512种生成颜色"}
        val limit=integer(p,"colorLimit",32,2,256)
        if(p.optBoolean("reduceColors",false)) {
            while(unique.size>limit) {
                cancelled();var best=Double.POSITIVE_INFINITY;var ai=0;var bi=1
                for(a in unique.indices)for(b in a+1 until unique.size) {
                    val d=(0..2).sumOf {(unique[a][it]-unique[b][it]).pow(2)}
                    if(d<best) {best=d;ai=a;bi=b}
                }
                unique[ai]=DoubleArray(3) {(unique[ai][it]+unique[bi][it])/2};unique.removeAt(bi)
            }
        }
        return unique.map {val rgb=ArtFilterColorSpace.fromLab(it);doubleArrayOf(rgb[0],rgb[1],rgb[2],1.0)}
    }
    private fun quantize(r:ArtFilterRaster,out:FloatArray,hidden:FloatArray,p:JSONObject,index:Boolean) {
        val palette=palette(p,index)
        r.work(palette.size.toLong()*4)
        val space=if(index)"lab" else choice(p,"distance","lab",setOf("lab","srgb","linear"))
        fun convert(c:DoubleArray)=when(space) {"lab"->ArtFilterColorSpace.lab(c);"srgb"->DoubleArray(3) {ArtFilterColorSpace.encode(c[it])};else->c}
        val colors=palette.map(::convert)
        val weights=vector(p,"weights",doubleArrayOf(1.0,1.0,1.0),0.0,10.0);require(weights.any {it>0})
        val dither=if(index)"none" else choice(p,"dither","none",setOf("none","ordered","nearest"))
        val scale=number(p,"ditherScale",.125,0.0,1.0)
        val alphaMode=if(index)"steps" else choice(p,"alphaMode","preserve",setOf("preserve","clip","index","dither","palette"))
        val alphaClip=number(p,"alphaClip",.5,0.0,1.0);val alphaIndex=integer(p,"alphaIndex",0,0,palette.lastIndex)
        val alphaSteps=integer(p,"alphaSteps",if(index)1 else 0,0,256)
        for(y in 0 until r.height) {cancelled();for(x in 0 until r.width) {
            val i=(y*r.width+x)*4;val alpha=r.source[i+3].toDouble()
            if(alpha==0.0)continue
            val sample=convert(DoubleArray(3) {r.straight(i,it)})
            val threshold=(bayer[(y%4)*4+x%4]+.5)/16
            if(dither=="ordered")for(c in 0..2)sample[c]+=(threshold-.5)*scale*(if(space=="lab")if(c==0)100 else 255 else 1)
            var first=0;var second=0;var d1=Double.POSITIVE_INFINITY;var d2=Double.POSITIVE_INFINITY
            for(k in colors.indices) {
                val distance=(0..2).sumOf {c->val norm=if(space=="lab")if(c==0)100.0 else 255.0 else 1.0
                    ((sample[c]-colors[k][c])/norm*weights[c]).pow(2)}
                if(distance<d1) {second=first;d2=d1;first=k;d1=distance} else if(distance<d2) {second=k;d2=distance}
            }
            var selected=first
            if(dither=="nearest" && d1>0 && d2.isFinite()) {
                val left=min(first,second);val right=max(first,second)
                val leftDistance=if(left==first)sqrt(d1) else sqrt(d2)
                selected=if(threshold<leftDistance/(sqrt(d1)+sqrt(d2)))right else left
            }
            val color=palette[selected]
            val nextAlpha=when(alphaMode) {"clip"->if(alpha<alphaClip)0.0 else 1.0
                "index"->if(selected==alphaIndex)0.0 else 1.0
                "dither"->if(alpha<threshold)0.0 else 1.0
                "palette"->alpha*color[3]
                "steps"->if(alphaSteps==0)alpha else (alpha*alphaSteps).roundToInt().toDouble()/alphaSteps
                else->alpha}
            for(c in 0..2)out[i+c]=(color[c]*nextAlpha).toFloat();out[i+3]=nextAlpha.toFloat()
            if(nextAlpha==0.0) {for(c in 0..2)hidden[i+c]=color[c].toFloat();hidden[i+3]=1f}
        }}
    }
    private fun halftone(r:ArtFilterRaster,out:FloatArray,p:JSONObject) {
        val mode=choice(p,"mode","intensity",setOf("intensity","channels","alpha"))
        val pattern=p.getJSONObject("pattern");fields(pattern,setOf("kind","size","angle","offset"))
        val kind=choice(pattern,"kind","dots",setOf("dots","lines","diamonds"))
        val size=number(pattern,"size",8.0,1.0,512.0);val angle=number(pattern,"angle",45.0,-360.0,360.0)
        val offset=if(pattern.has("offset"))pattern.getJSONArray("offset") else JSONArray("[0,0]")
        require(offset.length()==2 && offset.get(0) is Number && offset.get(1) is Number)
        require(offset.length()==2);val ox=offset.getDouble(0);val oy=offset.getDouble(1)
        require(ox.isFinite() && oy.isFinite() && abs(ox)<=65536 && abs(oy)<=65536)
        val angles=vector(p,"channelAngles",doubleArrayOf(15.0,45.0,75.0),-360.0,360.0)
        val hardness=number(p,"hardness",1.0,0.0,1.0);val aa=integer(p,"samples",2,1,4)
        val invert=p.optBoolean("invert",false)
        val space=choice(p,"space","srgb",setOf("srgb","linear"))
        val fg=color(p.optString("foreground","#FF000000"));val bg=color(p.optString("background","#FFFFFFFF"))
        r.work(aa.toLong()*aa*(if(mode=="channels")3 else 1)*8)
        fun ink(x:Int,y:Int,tone:Double,rotation:Double):Double {
            if(tone<=0)return 1.0
            if(tone>=1)return 0.0
            val a=rotation*PI/180;var sum=0.0
            for(sy in 0 until aa)for(sx in 0 until aa) {
                val px=x+(sx+.5)/aa-ox;val py=y+(sy+.5)/aa-oy
                val u=(px*cos(a)+py*sin(a))/size;val v=(-px*sin(a)+py*cos(a))/size
                val dx=abs(u-floor(u)-.5);val dy=abs(v-floor(v)-.5)
                val mask=when(kind) {"lines"->1-2*dx;"diamonds"->(1-dx-dy).coerceIn(0.0,1.0);else->(1-PI*(dx*dx+dy*dy)).coerceIn(0.0,1.0)}
                val delta=mask-tone
                sum+=if(hardness==1.0)if(delta>0)1.0 else 0.0 else (.5+delta/(1-hardness)).coerceIn(0.0,1.0)
            }
            return sum/(aa*aa)
        }
        for(y in 0 until r.height) {cancelled();for(x in 0 until r.width) {
            val i=(y*r.width+x)*4;val alpha=r.source[i+3].toDouble();if(alpha==0.0)continue
            val rgb=DoubleArray(3) {r.straight(i,it)}
            val work=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.encode(rgb[it])} else rgb
            when(mode) {
                "alpha" -> {
                    val tone=if(invert)alpha else 1-alpha
                    val coverage=ink(x,y,tone,angle)
                    for(c in 0..2)out[i+c]=(rgb[c]*coverage).toFloat();out[i+3]=coverage.toFloat()
                }
                "channels" -> for(c in 0..2) {
                    val tone=work[c].coerceIn(0.0,1.0).let {if(invert)1-it else it}
                    val coverage=ink(x,y,tone,angle+angles[c])
                    out[i+c]=((fg[c]*coverage+bg[c]*(1-coverage))*alpha).toFloat()
                }
                else -> {
                    val tone=ArtFilterColorSpace.luminance(work).coerceIn(0.0,1.0).let {if(invert)1-it else it}
                    val coverage=ink(x,y,tone,angle)
                    val a=(fg[3]*coverage+bg[3]*(1-coverage))*alpha
                    for(c in 0..2)out[i+c]=((fg[c]*fg[3]*coverage+bg[c]*bg[3]*(1-coverage))*alpha).toFloat()
                    out[i+3]=a.toFloat()
                }
            }
        }}
    }
}
