package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Color operations shared by the menu, AI preview and AI commit; buffers are premultiplied linear RGBA. */
internal object ArtColorFilters {
    val ids=setOf("filter.hsv_adjustment","filter.dodge","filter.burn","filter.slope_offset_power",
        "filter.auto_contrast","filter.color_balance","filter.levels","filter.cross_channel_adjustment_curves",
        "filter.color_adjustment_curves","filter.fast_color_overlay","filter.color_to_alpha","filter.normalize","filter.gradient_map")
    private val rgbNames=listOf("red","green","blue")
    private fun cancelled() {if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("滤镜已取消")}
    private fun number(p:JSONObject,key:String,default:Double,min:Double,max:Double):Double {
        val raw=if(p.has(key))p.get(key) else default
        require(raw is Number) {"$key 需要数字"};val v=raw.toDouble()
        require(v.isFinite() && v in min..max) {"$key 需要有限值 $min–$max"};return v
    }
    private fun choice(p:JSONObject,key:String,default:String,values:Set<String>)=p.optString(key,default).also {require(it in values)}
    private fun fields(o:JSONObject,allowed:Set<String>) {require(o.keys().asSequence().all {it in allowed}) {"配置包含未知字段"}}
    private fun vector(p:JSONObject,key:String,default:Double,min:Double,max:Double):DoubleArray {
        if(!p.has(key))return DoubleArray(3) {default}
        val a=p.getJSONArray(key);require(a.length()==3)
        return DoubleArray(3) {i->val v=a.get(i);require(v is Number);v.toDouble().also {require(it.isFinite() && it in min..max)}}
    }
    private fun color(value:String):DoubleArray {
        require(value.matches(Regex("#[0-9a-fA-F]{8}"))) {"颜色需要#AARRGGBB"}
        val c=value.drop(1).toLong(16)
        return doubleArrayOf(ArtFilterColorSpace.decode(((c shr 16) and 255)/255.0),
            ArtFilterColorSpace.decode(((c shr 8) and 255)/255.0),ArtFilterColorSpace.decode((c and 255)/255.0),((c shr 24) and 255)/255.0)
    }
    private fun bounded(c:DoubleArray,clip:Boolean):DoubleArray {
        require(clip || c.all {it in 0.0..1.0}) {"此颜色模式要求工作域RGB为0–1；可显式开启clipInput，或选择其他HDR处理"}
        return if(clip)DoubleArray(3) {c[it].coerceIn(0.0,1.0)} else c
    }
    private class Levels(p:JSONObject) {
        val black=number(p,"black",0.0,-16.0,16.0)
        val white=number(p,"white",1.0,-16.0,16.0)
        val gamma=number(p,"gamma",1.0,.01,100.0)
        val outBlack=number(p,"outBlack",0.0,-16.0,16.0)
        val outWhite=number(p,"outWhite",1.0,-16.0,16.0)
        init {fields(p,setOf("black","white","gamma","outBlack","outWhite"));require(white>black)}
        fun at(v:Double,clip:Boolean):Double {
            val t=((v-black)/(white-black)).let {if(clip)it.coerceIn(0.0,1.0) else it}
            return outBlack+(outWhite-outBlack)*sign(t)*abs(t).pow(1/gamma)
        }
    }
    private data class Cross(val driver:String,val target:String,val curve:ArtFilterCurve)
    private data class Stop(val position:Double,val rgba:DoubleArray)

    fun process(action:String,source:FloatArray,w:Int,h:Int,p:JSONObject,budgetBytes:Long=256L*1024*1024):FloatArray {
        require(action in ids && w>0 && h>0 && w.toLong()*h*4==source.size.toLong())
        require(w.toLong()*h*64<=budgetBytes) {"颜色滤镜工作内存超过预算"}
        for(i in source.indices) {if(i%16384==0)cancelled();val v=source[i]
            require(v.isFinite() && if(i%4==3)v in 0f..1f else abs(v)<=65504f)}
        val defaultSpace=if(action in setOf("filter.color_to_alpha","filter.normalize","filter.gradient_map")) "linear" else "srgb"
        val space=choice(p,"space",defaultSpace,setOf("linear","srgb"))
        val interpolation=choice(p,"interpolation","smooth",setOf("linear","smooth"))
        val outside=choice(p,"outside","linear",setOf("linear","constant"))
        val enabled=booleanArrayOf(p.optBoolean("red",true),p.optBoolean("green",true),p.optBoolean("blue",true))
        val alphaEnabled=p.optBoolean("alpha",action in setOf("filter.levels","filter.color_adjustment_curves","filter.cross_channel_adjustment_curves","filter.color_to_alpha","filter.gradient_map"))
        require(enabled.any {it} || alphaEnabled) {"至少选择一个通道"}
        val clip=p.optBoolean("clipInput",false)
        val curves=mutableMapOf<String,ArtFilterCurve>()
        val cross=mutableListOf<Cross>()
        val levels=mutableMapOf<String,Levels>()
        if(action=="filter.color_adjustment_curves") {
            val o=if(p.has("curves"))p.getJSONObject("curves") else JSONObject().put("master",JSONArray("[[0,0],[1,1]]"))
            fields(o,(rgbNames+listOf("master","alpha")).toSet())
            o.keys().forEach {curves[it]=ArtFilterCurve(o.getJSONArray(it),interpolation,outside)}
        }
        if(action=="filter.cross_channel_adjustment_curves") {
            val a=if(p.has("rules"))p.getJSONArray("rules") else JSONArray();require(a.length()<=16)
            val allowed=(rgbNames+listOf("hue","saturation","value","luminance","alpha")).toSet()
            for(i in 0 until a.length()) {
                val o=a.getJSONObject(i);fields(o,setOf("driver","target","points"))
                require(o.get("driver") is String && o.get("target") is String)
                val driver=o.getString("driver");val target=o.getString("target")
                require(driver in allowed && target in allowed+"master")
                val curve=ArtFilterCurve(o.getJSONArray("points"),interpolation,"constant")
                if(!curve.constant(.5))cross.add(Cross(driver,target,curve))
            }
        }
        if(action=="filter.levels") {
            val o=if(p.has("levels"))p.getJSONObject("levels") else JSONObject().put("master",JSONObject())
            fields(o,(rgbNames+listOf("master","alpha")).toSet());o.keys().forEach {levels[it]=Levels(o.getJSONObject(it))}
        }
        // Curves are tiny bounded configurations; refuse large images with excessive per-pixel curve work.
        val complexity=when(action) {"filter.color_adjustment_curves"->1+curves.size*96
            "filter.cross_channel_adjustment_curves"->1+cross.size*32;else->1}
        require(w.toLong()*h*complexity<=250_000_000L) {"曲线运算量超过单次预算，请减少曲线或画布"}
        val hue=number(p,"hue",0.0,-360.0,360.0)/360
        val saturation=number(p,"saturation",0.0,-1.0,1.0);val value=number(p,"value",0.0,-1.0,1.0)
        val hsl=choice(p,"model","hsv",setOf("hsv","hsl"))=="hsl"
        val colorize=p.optBoolean("colorize",false)
        val slope=vector(p,"slope",1.0,-16.0,16.0);val offset=vector(p,"offset",0.0,-16.0,16.0)
        val power=vector(p,"power",1.0,.01,16.0)
        val exposure=number(p,"exposure",.5,0.0,1.0)
        val range=choice(p,"range","midtones",setOf("shadows","midtones","highlights"))
        val shadows=vector(p,"shadows",0.0,-1.0,1.0);val midtones=vector(p,"midtones",0.0,-1.0,1.0);val highlights=vector(p,"highlights",0.0,-1.0,1.0)
        val overlay=color(p.optString("color","#FFFFFFFF"))
        val overlayOpacity=number(p,"opacity",1.0,0.0,1.0)*overlay[3]
        val blend=choice(p,"blend","normal",setOf("normal","multiply","screen","overlay","darken","lighten","add","difference"))
        val threshold=number(p,"tolerance",1.0,.000001,1.0)
        val levelMode=choice(p,"mode","channels",setOf("channels","lightness"))
        if(action=="filter.levels" && levelMode=="lightness")
            require(levels.keys.all {it in setOf("master","alpha")}) {"明度模式只接受master和alpha色阶"}
        val inputBlack=number(p,"inputBlack",0.0,-65504.0,65504.0);val inputWhite=number(p,"inputWhite",1.0,-65504.0,65504.0)
        require(inputWhite>inputBlack)
        val stops=mutableListOf<Stop>()
        if(action=="filter.gradient_map") {
            val a=if(p.has("stops"))p.getJSONArray("stops") else JSONArray("[{\"position\":0,\"rgba\":[0,0,0,1]},{\"position\":1,\"rgba\":[1,1,1,1]}]")
            require(a.length() in 2..16)
            for(i in 0 until a.length()) {
                val o=a.getJSONObject(i);fields(o,setOf("position","rgba"))
                require(o.has("position")) {"渐变节点缺少position"}
                val position=number(o,"position",0.0,0.0,1.0)
                val rgba=o.getJSONArray("rgba");require(rgba.length()==4)
                val c=DoubleArray(4) {j->val v=rgba.get(j);require(v is Number);v.toDouble().also {require(it.isFinite() && if(j==3)it in 0.0..1.0 else abs(it)<=65504)}}
                if(i>0)require(position>stops.last().position)
                stops.add(Stop(position,c))
            }
            require(stops.first().position==0.0 && stops.last().position==1.0)
        }
        var contrastLow=0.0;var contrastHigh=100.0
        if(action=="filter.auto_contrast") {
            val cut=number(p,"percentile",.5,0.0,49.0)/100
            val histogram=IntArray(4096);var count=0L
            for(i in source.indices step 4) {
                if(i%16384==0)cancelled()
                if(source[i+3]>0) {
                    val c=DoubleArray(3) {source[i+it].toDouble()/source[i+3]}
                    val lightness=ArtFilterColorSpace.lab(c)[0]
                    histogram[(lightness.coerceIn(0.0,100.0)*40.95).roundToInt()]++;count++
                }
            }
            if(count>0) {
                fun percentile(f:Double):Double {var total=0L;for(i in histogram.indices) {total+=histogram[i];if(total>count*f)return i/40.95};return 100.0}
                contrastLow=percentile(cut);contrastHigh=percentile(1-cut)
            } else contrastHigh=contrastLow
        }
        val out=source.copyOf()
        for(i in source.indices step 4) {
            if(i%16384==0)cancelled()
            val oldAlpha=source[i+3].toDouble()
            // Do not synthesize hidden colors into alpha-zero emissive pixels in these appearance operations.
            if(oldAlpha==0.0)continue
            val linear=DoubleArray(3) {source[i+it]/oldAlpha}
            var c=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.encode(linear[it])} else linear.copyOf()
            var alpha=oldAlpha
            when(action) {
                "filter.hsv_adjustment" -> if(hue!=0.0 || saturation!=0.0 || value!=0.0 || colorize || clip) {
                    val cyl=ArtFilterColorSpace.cylindrical(bounded(c,clip),hsl)
                    cyl[0]=if(colorize)(hue%1+1)%1 else (cyl[0]+hue)%1
                    cyl[1]=if(colorize)saturation.coerceIn(0.0,1.0) else if(saturation>=0)cyl[1]+(1-cyl[1])*saturation else cyl[1]*(1+saturation)
                    cyl[2]=if(value>=0)cyl[2]+(1-cyl[2])*value else cyl[2]*(1+value)
                    c=ArtFilterColorSpace.fromCylindrical(cyl,hsl)
                }
                "filter.dodge","filter.burn" -> {
                    val dodge=action=="filter.dodge"
                    for(j in 0..2)c[j]=when(range) {
                        "highlights"->c[j]*(1+(if(dodge)1 else -1)*exposure/3)
                        "shadows"->if(dodge)c[j]+exposure/3*(1-c[j]) else max(0.0,(c[j]-exposure/3)/(1-exposure/3))
                        else->{require(c[j]>=0) {"中间调幂运算需要非负工作域通道"};c[j].pow(if(dodge)1/(1+exposure) else 1+exposure/3)}
                    }
                }
                "filter.slope_offset_power" -> for(j in 0..2) {
                    val base=c[j]*slope[j]+offset[j]
                    require(base>=0 || power[j]==floor(power[j])) {"负底数不能使用分数次幂；请调整偏移或幂"}
                    c[j]=base.pow(power[j])
                }
                "filter.color_balance" -> if(shadows.any {it!=0.0} || midtones.any {it!=0.0} || highlights.any {it!=0.0} || clip) {
                    c=bounded(c,clip);val old=ArtFilterColorSpace.cylindrical(c,true)
                    val l=old[2]
                    val sw=(((l-.333)/-.25)+.5).coerceIn(0.0,1.0)*.7
                    val mw=(((l-.333)/.25)+.5).coerceIn(0.0,1.0)*(((l+.333-1)/-.25)+.5).coerceIn(0.0,1.0)*.7
                    val hw=(((l+.333-1)/.25)+.5).coerceIn(0.0,1.0)*.7
                    c=DoubleArray(3) {(c[it]+shadows[it]*sw+midtones[it]*mw+highlights[it]*hw).coerceIn(0.0,1.0)}
                    if(p.optBoolean("preserveLuminosity",true)) {
                        val next=ArtFilterColorSpace.cylindrical(c,true);next[2]=old[2];c=ArtFilterColorSpace.fromCylindrical(next,true)
                    }
                }
                "filter.levels" -> {
                    if(levelMode=="lightness") {
                        val lab=ArtFilterColorSpace.lab(linear)
                        levels["master"]?.let {lab[0]=it.at(lab[0]/100,clip)*100}
                        val mapped=ArtFilterColorSpace.fromLab(lab);c=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.encode(mapped[it])} else mapped
                    } else for(j in 0..2) {levels["master"]?.let {c[j]=it.at(c[j],clip)};levels[rgbNames[j]]?.let {c[j]=it.at(c[j],clip)}}
                    levels["alpha"]?.let {alpha=it.at(alpha,true).coerceIn(0.0,1.0)}
                }
                "filter.color_adjustment_curves" -> {
                    for(j in 0..2) {curves["master"]?.let {c[j]=it.at(c[j])};curves[rgbNames[j]]?.let {c[j]=it.at(c[j])}}
                    curves["alpha"]?.let {alpha=it.at(alpha).coerceIn(0.0,1.0)}
                }
                "filter.cross_channel_adjustment_curves" -> for(rule in cross) {
                    val usesHSV=rule.driver in setOf("hue","saturation","value") || rule.target in setOf("hue","saturation","value")
                    val hsv=if(usesHSV)ArtFilterColorSpace.cylindrical(bounded(c,clip),false) else doubleArrayOf(0.0,0.0,0.0)
                    val driver=when(rule.driver) {"red"->c[0];"green"->c[1];"blue"->c[2];"alpha"->alpha
                        "hue"->hsv[0];"saturation"->hsv[1];"value"->hsv[2];else->ArtFilterColorSpace.luminance(c)}
                    val delta=2*rule.curve.at(driver)-1
                    when(rule.target) {
                        "master"->for(j in 0..2)c[j]+=delta
                        "alpha"->alpha=(alpha+delta).coerceIn(0.0,1.0)
                        "luminance"->for(j in 0..2)c[j]+=delta
                        "hue","saturation","value"->{val channel=when(rule.target) {"hue"->0;"saturation"->1;else->2};hsv[channel]+=delta
                            if(channel>0)hsv[channel]=hsv[channel].coerceIn(0.0,1.0);c=ArtFilterColorSpace.fromCylindrical(hsv,false)}
                        else->c[rgbNames.indexOf(rule.target)]+=delta
                    }
                }
                "filter.auto_contrast" -> if(contrastHigh>contrastLow) {
                    val lab=ArtFilterColorSpace.lab(linear)
                    lab[0]=((lab[0]-contrastLow)/(contrastHigh-contrastLow)).coerceIn(0.0,1.0)*100
                    val mapped=ArtFilterColorSpace.fromLab(lab);c=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.encode(mapped[it])} else mapped
                }
                "filter.fast_color_overlay" -> for(j in 0..2) {
                    val target=if(space=="srgb")ArtFilterColorSpace.encode(overlay[j]) else overlay[j]
                    val v=when(blend) {"multiply"->c[j]*target;"screen"->1-(1-c[j])*(1-target)
                        "overlay"->if(c[j]<=.5)2*c[j]*target else 1-2*(1-c[j])*(1-target)
                        "darken"->min(c[j],target);"lighten"->max(c[j],target);"add"->c[j]+target
                        "difference"->abs(c[j]-target);else->target}
                    c[j]=c[j]*(1-overlayOpacity)+v*overlayOpacity
                }
                "filter.color_to_alpha" -> {
                    val target=DoubleArray(3) {if(space=="srgb")ArtFilterColorSpace.encode(overlay[it]) else overlay[it]}
                    val difference=(0..2).maxOf {abs(c[it]-target[it])}
                    val factor=(difference/threshold).coerceIn(0.0,1.0)
                    alpha=oldAlpha*factor
                    c=if(factor==0.0)DoubleArray(3) else DoubleArray(3) {(c[it]-target[it])/factor+target[it]}
                }
                "filter.normalize" -> {
                    val vector=DoubleArray(3) {2*linear[it]-1};val length=sqrt(vector.sumOf {it*it})
                    val mapped=if(length==0.0)DoubleArray(3) {.5} else DoubleArray(3) {vector[it]/length*.5+.5}
                    c=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.encode(mapped[it])} else mapped
                }
                "filter.gradient_map" -> {
                    val t=((ArtFilterColorSpace.luminance(linear)-inputBlack)/(inputWhite-inputBlack)).coerceIn(0.0,1.0)
                    var k=0;while(k<stops.size-2 && t>stops[k+1].position)k++
                    val a=stops[k];val b=stops[k+1];val mix=(t-a.position)/(b.position-a.position)
                    val opacity=a.rgba[3]*(1-mix)+b.rgba[3]*mix
                    val mapped=DoubleArray(3) {j->
                        val start=if(space=="srgb")ArtFilterColorSpace.encode(a.rgba[j]) else a.rgba[j]
                        val end=if(space=="srgb")ArtFilterColorSpace.encode(b.rgba[j]) else b.rgba[j]
                        if(opacity==0.0)0.0 else (start*a.rgba[3]*(1-mix)+end*b.rgba[3]*mix)/opacity
                    }
                    c=mapped;alpha=oldAlpha*opacity
                }
            }
            val result=if(space=="srgb")DoubleArray(3) {ArtFilterColorSpace.decode(c[it])} else c
            val finalAlpha=if(alphaEnabled)alpha else oldAlpha
            require(finalAlpha.isFinite() && finalAlpha in 0.0..1.0)
            for(j in 0..2) {
                val v=(if(enabled[j])result[j] else linear[j])*finalAlpha
                require(v.isFinite() && abs(v)<=65504) {"颜色滤镜结果超出有限浮点范围"}
                out[i+j]=v.toFloat()
            }
            out[i+3]=finalAlpha.toFloat()
        }
        return out
    }
}
