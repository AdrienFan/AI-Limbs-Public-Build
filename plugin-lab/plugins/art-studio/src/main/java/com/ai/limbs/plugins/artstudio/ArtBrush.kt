package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Filter once before committing. Versioned brush snapshots keep old strokes immutable. */
internal object ArtBrush {
    val tools=linkedMapOf("ink" to "自由画笔","pencil" to "铅笔","soft" to "软笔","spray" to "喷枪",
        "eraser" to "橡皮擦","calligraphy" to "斜头书法笔（栅格）")
    val modes=linkedMapOf("none" to "无","weighted" to "加权平滑","stabilizer" to "稳定器","pixel_perfect" to "像素完美")
    val sensors=linkedMapOf("pressure" to "压力","speed" to "速度","tilt" to "倾斜",
        "rotation" to "笔方向角","direction" to "轨迹方向","random" to "随机")
    val channels=linkedMapOf("size" to "笔径","opacity" to "流量","spacing" to "间距","angle" to "角度")
    const val MAX_SAMPLES=10000
    const val MAX_DABS=60000
    const val MAX_PARTICLES=240000
    data class Sample(val x:Double,val y:Double,val pressure:Double,val time:Double,val tilt:Double=0.0,val rotation:Double=0.0) {
        fun json()=JSONArray().put(x).put(y).put(pressure).put(time).put(tilt).put(rotation)
        fun mix(b:Sample,t:Double)=Sample(x+(b.x-x)*t,y+(b.y-y)*t,pressure+(b.pressure-pressure)*t,
            time+(b.time-time)*t,tilt+(b.tilt-tilt)*t,rotation+(b.rotation-rotation)*t)
    }
    data class Dab(val sample:Sample,val size:Double,val flow:Double,val angle:Double,val ordinal:Int)
    fun supports(tool:String)=tool in tools || tool in setOf("mirror","dyna","line","rectangle","ellipse","polygon","polyline","bezier")
    fun engineTool(stroke:JSONObject)=if(stroke.getString("tool") in setOf("mirror","dyna","line","rectangle","ellipse","polygon","polyline","bezier"))stroke.optString("brushTool","ink") else stroke.getString("tool")
    fun defaults(tool:String):JSONObject {
        require(tool in tools)
        val dynamics=JSONObject()
        channels.keys.forEach { dynamics.put(it,JSONObject().put("enabled",it=="size").put("sensor","pressure")
            .put("curve",JSONArray().put(JSONArray().put(0).put(0.1)).put(JSONArray().put(1).put(1)))) }
        return JSONObject().put("version",1)
            .put("tip",JSONObject().put("shape",if(tool=="calligraphy")"ellipse" else "round")
                .put("ratio",if(tool=="calligraphy")0.16 else 1.0).put("angle",if(tool=="calligraphy")45.0 else 0.0)
                .put("hardness",if(tool=="soft")0.0 else 1.0))
            .put("texture",JSONObject().put("kind",if(tool=="pencil")"grain" else "none")
                .put("strength",if(tool=="pencil")0.55 else 0.5).put("scale",1.0).put("invert",false))
            .put("spacing",if(tool=="spray")0.2 else 0.12)
            .put("flow",when(tool) {"pencil"->0.45;"soft"->0.12;"spray"->0.3;else->1.0})
            .put("scatter",if(tool=="spray")0.5 else 0.0).put("count",if(tool=="spray")12 else 1)
            .put("jitter",0.0).put("airbrushRate",if(tool=="spray")24.0 else 0.0).put("dynamics",dynamics)
            .put("smoothing",JSONObject().put("mode","none").put("window",12).put("strength",0.7)
                .put("delay",12.0).put("smoothPressure",true).put("finish",true))
    }
    private fun keys(p:JSONObject,allowed:Set<String>) {
        require(p.keys().asSequence().all { it in allowed }) { "笔刷含未知参数" }
    }
    private fun number(p:JSONObject,key:String,range:ClosedFloatingPointRange<Double>):Double {
        val v=p.getDouble(key);require(v.isFinite() && v in range) { "$key 超出范围" };return v
    }
    private fun merge(base:JSONObject,patch:JSONObject):JSONObject {
        val out=JSONObject(base.toString())
        patch.keys().forEach { k->val v=patch.get(k)
            out.put(k,if(v is JSONObject && out.opt(k) is JSONObject)merge(out.getJSONObject(k),v) else v) }
        return out
    }
    fun settings(tool:String,patch:JSONObject=JSONObject(),base:JSONObject=defaults(tool)):JSONObject {
        require(tool in tools)
        val out=merge(base,patch)
        // Changing image mode to a geometric tip/texture removes its previous asset binding.
        for((part,field) in listOf("tip" to "shape","texture" to "kind")) {
            val changed=patch.optJSONObject(part)
            if(changed!=null && changed.has(field) && changed.getString(field)!="image" && !changed.has("asset"))
                out.getJSONObject(part).remove("asset")
        }
        keys(out,setOf("version","tip","texture","spacing","flow","scatter","count","jitter","airbrushRate","dynamics","smoothing"))
        require(out.getDouble("version")==1.0) { "笔刷版本不受支持" }
        val tip=out.getJSONObject("tip")
        keys(tip,setOf("shape","ratio","angle","hardness","asset"))
        require(tip.getString("shape") in setOf("round","ellipse","square","image"))
        number(tip,"ratio",0.05..1.0);number(tip,"angle",-360.0..360.0);number(tip,"hardness",0.0..1.0)
        val texture=out.getJSONObject("texture")
        keys(texture,setOf("kind","strength","scale","invert","asset"))
        require(texture.getString("kind") in setOf("none","grain","canvas","checker","image"))
        number(texture,"strength",0.0..1.0);number(texture,"scale",0.1..16.0);texture.getBoolean("invert")
        for(part in listOf(tip,texture)) {
            val image=if(part===tip)part.getString("shape")=="image" else part.getString("kind")=="image"
            if(image)require(part.getString("asset").matches(Regex("[a-f0-9-]{36}"))) { "图像笔刷需要资源ID" }
            else require(!part.has("asset")) { "仅图像笔刷使用asset" }
        }
        number(out,"spacing",0.02..2.0);number(out,"flow",0.0..1.0);number(out,"scatter",0.0..2.0)
        number(out,"jitter",0.0..360.0);number(out,"airbrushRate",0.0..120.0)
        val count=number(out,"count",1.0..64.0);require(count==floor(count))
        val dynamics=out.getJSONObject("dynamics");keys(dynamics,channels.keys)
        channels.keys.forEach { val r=dynamics.getJSONObject(it);keys(r,setOf("enabled","sensor","curve"))
            r.getBoolean("enabled");require(r.getString("sensor") in sensors);validateCurve(r.getJSONArray("curve")) }
        val s=out.getJSONObject("smoothing");keys(s,setOf("mode","window","strength","delay","smoothPressure","finish"))
        require(s.getString("mode") in modes)
        val window=number(s,"window",2.0..64.0);require(window==floor(window))
        number(s,"strength",0.0..1.0);number(s,"delay",0.0..128.0);s.getBoolean("smoothPressure");s.getBoolean("finish")
        return out
    }
    fun validateCurve(c:JSONArray) {
        require(c.length() in 2..16);var last=-1.0
        for(i in 0 until c.length()) {
            val p=c.getJSONArray(i);require(p.length()==2)
            val x=p.getDouble(0);val y=p.getDouble(1)
            require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && x>last) { "曲线x须严格递增，x/y为0–1" }
            if(i==0)require(x==0.0);if(i==c.length()-1)require(x==1.0);last=x
        }
    }
    fun curve(c:JSONArray,x:Double):Double {
        val value=x.coerceIn(0.0,1.0)
        for(i in 1 until c.length()) {
            val a=c.getJSONArray(i-1);val b=c.getJSONArray(i)
            if(value<=b.getDouble(0))return a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*
                (value-a.getDouble(0))/(b.getDouble(0)-a.getDouble(0))
        }
        return c.getJSONArray(c.length()-1).getDouble(1)
    }
    fun samples(raw:JSONArray):List<Sample> {
        require(raw.length() in 1..MAX_SAMPLES);var time=-1.0
        return (0 until raw.length()).map { i->
            val p=raw.getJSONArray(i);require(p.length() in 2..6)
            val s=Sample(p.getDouble(0),p.getDouble(1),p.optDouble(2,1.0),p.optDouble(3,i*16.0),p.optDouble(4,0.0),p.optDouble(5,0.0))
            require(s.x.isFinite() && s.y.isFinite() && abs(s.x)<=1_000_000 && abs(s.y)<=1_000_000)
            require(s.pressure.isFinite() && s.pressure in 0.0..1.0 && s.time.isFinite() &&
                s.time in 0.0..180001.0 && s.time>=time && s.tilt.isFinite() && s.tilt in 0.0..1.0 &&
                s.rotation.isFinite() && s.rotation in 0.0..1.0) { "输入传感器或时间无效" }
            time=s.time;s
        }
    }
    private fun weighted(input:List<Sample>,s:JSONObject):List<Sample> = input.indices.map { i->
        val window=s.getInt("window");var sum=0.0;var x=0.0;var y=0.0;var pressure=0.0
        for(j in max(0,i-window+1)..i) {
            val age=(i-j).toDouble()/window;val w=exp(-4*age*age);sum+=w
            x+=input[j].x*w;y+=input[j].y*w;pressure+=input[j].pressure*w
        }
        val p=input[i];val strength=s.getDouble("strength")
        p.copy(x=p.x+(x/sum-p.x)*strength,y=p.y+(y/sum-p.y)*strength,
            pressure=if(s.getBoolean("smoothPressure"))p.pressure+(pressure/sum-p.pressure)*strength else p.pressure)
    }
    private fun pixelPath(input:List<Sample>):List<Sample> {
        val out=mutableListOf<Sample>()
        fun add(p:Sample) {
            if(out.isNotEmpty() && out.last().x==p.x && out.last().y==p.y){out[out.lastIndex]=p;return}
            if(out.size>=2) {
                val a=out[out.size-2];val b=out.last()
                if(abs(a.x-p.x)==1.0 && abs(a.y-p.y)==1.0 &&
                    ((a.x==b.x && b.y==p.y)||(a.y==b.y && b.x==p.x)))out.removeAt(out.lastIndex)
            }
            require(out.size<MAX_SAMPLES) { "像素路径过长，请分段绘制" };out.add(p)
        }
        add(input.first().copy(x=floor(input.first().x)+0.5,y=floor(input.first().y)+0.5))
        for(i in 1 until input.size) {
            val a=input[i-1];val b=input[i];var x=floor(a.x).toInt();var y=floor(a.y).toInt()
            val ex=floor(b.x).toInt();val ey=floor(b.y).toInt();val dx=abs(ex-x);val dy=abs(ey-y)
            val length=max(dx,dy);require(length<=MAX_SAMPLES)
            val sx=if(x<ex)1 else -1;val sy=if(y<ey)1 else -1;var error=dx-dy;var n=0
            while(x!=ex || y!=ey) {
                val twice=2*error;if(twice> -dy){error-=dy;x+=sx};if(twice<dx){error+=dx;y+=sy}
                n++;add(a.mix(b,n.toDouble()/length).copy(x=x+0.5,y=y+0.5))
            }
        }
        return out
    }
    fun process(input:List<Sample>,brush:JSONObject,finished:Boolean):List<Sample> {
        val s=brush.getJSONObject("smoothing")
        val result=when(s.getString("mode")) {
            "none"->input
            "pixel_perfect"->pixelPath(input)
            "weighted"->weighted(input,s)
            "stabilizer"->{
                val source=if(s.getBoolean("smoothPressure"))weighted(input,s).mapIndexed {i,p -> input[i].copy(pressure=p.pressure)} else input
                var cursor=input.first();val out=mutableListOf(cursor)
                source.drop(1).forEach { p->
                    val d=hypot(p.x-cursor.x,p.y-cursor.y);val t=if(d>s.getDouble("delay"))(d-s.getDouble("delay"))/d else 0.0
                    cursor=cursor.mix(p,t).copy(time=p.time,pressure=p.pressure,tilt=p.tilt,rotation=p.rotation);out.add(cursor)
                };out
            }
            else->error("轨迹模式无效")
        }
        if(!finished || !s.getBoolean("finish") || s.getString("mode") !in setOf("weighted","stabilizer"))return result
        val out=result.toMutableList();val a=out.last();val b=input.last()
        if(hypot(a.x-b.x,a.y-b.y)>0.001) {
            val n=min(16,MAX_SAMPLES-out.size);require(n>0)
            for(i in 1..n)out.add(a.mix(b,i.toDouble()/n).copy(time=b.time+i*0.01))
        }
        return out
    }
    fun prepare(stroke:JSONObject,brush:JSONObject,seed:Int,finished:Boolean=true):JSONObject {
        require(seed>=0);val settings=settings(engineTool(stroke),brush)
        if(stroke.getString("tool")=="line" || stroke.getString("tool") in ArtFigure.tools || stroke.getString("tool") in ArtRasterPath.tools) {
            // Figure geometry must reach both endpoints; cursor smoothing and timed airbrushing are freehand-only.
            if(settings.getJSONObject("smoothing").getString("mode")!="pixel_perfect")settings.getJSONObject("smoothing").put("mode","none")
            settings.put("airbrushRate",0)
            if(stroke.getString("tool")=="line" && !stroke.getBoolean("useSensors"))channels.keys.forEach {key ->
                val rule=settings.getJSONObject("dynamics").getJSONObject(key)
                if(rule.getString("sensor") in setOf("pressure","speed","tilt","rotation"))rule.put("enabled",false)
            }
        }
        val raw=stroke.getJSONArray("points");val output=process(samples(raw),settings,finished)
        val result=JSONObject(stroke.toString()).put("brush",settings).put("brushSeed",seed)
            .put("brushInput",JSONArray(raw.toString())).put("brushProcessed",true).put("points",JSONArray(output.map {it.json()}))
        dabs(result) { };if(result.getString("tool")=="mirror")ArtMirror.requireBudget(result);return result
    }
    fun validateStored(stroke:JSONObject) {
        require(stroke.getBoolean("brushProcessed") && stroke.getInt("brushSeed")>=0)
        settings(engineTool(stroke),stroke.getJSONObject("brush"))
        if(stroke.getString("tool")=="mirror")ArtMirror.validateStored(stroke)
        if(stroke.getString("tool")=="dyna")ArtDyna.validateStored(stroke)
        if(stroke.getString("tool")=="line")ArtLine.validateStored(stroke)
        if(stroke.getString("tool") in ArtFigure.tools)ArtFigure.validateStored(stroke)
        if(stroke.getString("tool") in ArtRasterPath.tools)ArtRasterPath.validateStored(stroke)
        samples(stroke.getJSONArray("brushInput"));samples(stroke.getJSONArray("points"))
    }
    fun noise(seed:Int,index:Int,salt:Int):Double {
        var x=seed.toLong() xor (index.toLong()*0x45d9f3bL) xor (salt.toLong()*0x119de1f3L)
        x=(x xor (x ushr 16))*0x45d9f3bL;x=(x xor (x ushr 16))*0x45d9f3bL
        return ((x xor (x ushr 16)) and 0x7fffffffL).toDouble()/Int.MAX_VALUE
    }
    fun dabs(stroke:JSONObject,emit:(Dab)->Unit) {
        val brush=stroke.getJSONObject("brush");val input=samples(stroke.getJSONArray("points"))
        val width=stroke.getDouble("width");require(width.isFinite() && width in 0.1..512.0)
        val seed=stroke.getInt("brushSeed");val count=brush.getInt("count")
        var n=0;var direction=0.0;var last=input.first()
        fun stamp(p:Sample,previous:Sample):Double {
            require(n<MAX_DABS && (n.toLong()+1)*count<=MAX_PARTICLES) { "笔触预算超限，请增加间距或分段" }
            val d=hypot(p.x-previous.x,p.y-previous.y)
            if(d>0.00001)direction=(atan2(p.y-previous.y,p.x-previous.x)/(2*PI)+1)%1
            val values=mapOf("pressure" to p.pressure,"speed" to (d/max(1.0,p.time-previous.time)).coerceIn(0.0,1.0),
                "tilt" to p.tilt,"rotation" to p.rotation,"direction" to direction,"random" to noise(seed,n,17))
            fun dynamic(channel:String):Double {
                val r=brush.getJSONObject("dynamics").getJSONObject(channel)
                return if(r.getBoolean("enabled"))curve(r.getJSONArray("curve"),values.getValue(r.getString("sensor"))) else 1.0
            }
            val size=max(0.1,width*dynamic("size"))
            val angle=brush.getJSONObject("tip").getDouble("angle")+
                if(brush.getJSONObject("dynamics").getJSONObject("angle").getBoolean("enabled"))360*dynamic("angle") else 0.0
            emit(Dab(p,size,brush.getDouble("flow")*dynamic("opacity"),angle,n++));last=p
            return max(0.25,size*brush.getDouble("spacing")*dynamic("spacing"))
        }
        var remaining=stamp(input.first(),input.first())
        val pixel=brush.getJSONObject("smoothing").getString("mode")=="pixel_perfect"
        val rate=brush.getDouble("airbrushRate");val period=if(rate>0)1000/rate else Double.POSITIVE_INFINITY
        var next=input.first().time+period
        for(i in 1 until input.size) {
            val a=input[i-1];val b=input[i];val length=hypot(b.x-a.x,b.y-a.y)
            if(pixel){if(length>0)stamp(b,a);continue}
            var walked=0.0
            while(length>0 && length-walked>=remaining) {
                walked+=remaining;remaining=stamp(a.mix(b,walked/length),a)
            }
            remaining-=length-walked
            while(next<=b.time) {
                val t=if(b.time>a.time)(next-a.time)/(b.time-a.time) else 1.0
                stamp(a.mix(b,t.coerceIn(0.0,1.0)),a);next+=period
            }
        }
        if(!pixel && input.size>1 && hypot(input.last().x-last.x,input.last().y-last.y)>0.00001)stamp(input.last(),input[input.lastIndex-1])
    }
    fun assetIds(brush:JSONObject)=listOf("tip","texture").mapNotNull { key->
        val p=brush.getJSONObject(key);if(p.has("asset"))p.getString("asset") else null
    }.toSet()
    fun renderOverhead(state:JSONObject,width:Int,height:Int):Long {
        var gradientBytes=0L
        val layers=state.getJSONArray("layers");var enabled=false;var figure=false;var softBytes=0L;val assets=mutableSetOf<String>()
        state.optJSONObject("selection")?.takeIf {it.has("coverage")}?.let {s->softBytes=(s.getInt("maskWidth")+2L)*(s.getInt("maskHeight")+2)*4}
        for(i in 0 until layers.length()) {
            val layer=layers.getJSONObject(i)
            if(layer.getString("kind")=="vector" && ArtShapes.items(layer).any {it.has("objectStyle")})enabled=true
            fun reserve(s:JSONObject?) {if(s?.has("coverage")==true)softBytes=maxOf(softBytes,width.toLong()*height*4+(s.getInt("maskWidth")+2L)*(s.getInt("maskHeight")+2)*4)}
            layer.optJSONArray("contentOrder")?.let {order->for(n in 0 until order.length())reserve(order.getJSONObject(n).optJSONObject("selection"))}
            val strokes=layers.getJSONObject(i).getJSONArray("strokes")
            for(n in 0 until strokes.length()) {
                val stroke=strokes.getJSONObject(n);reserve(stroke.optJSONObject("selection"))
                if(stroke.has("gradientVersion"))gradientBytes=maxOf(gradientBytes,ArtGradient.overhead(stroke))
                stroke.optJSONObject("brush")?.let {enabled=true;assets.addAll(assetIds(it))}
                if(stroke.has("figureVersion") || stroke.has("pathVersion")) {enabled=true;figure=true;assets.addAll(ArtFigure.assetIds(stroke))}
            }
        }
        if(!enabled)return softBytes+gradientBytes
        // One opacity/erase layer plus bounded decoded-image cache and temporary masks/arrays.
        return softBytes+gradientBytes+width.toLong()*height*(if(figure)8 else 4)+if(assets.isEmpty())128L*128*16 else (min(8,assets.size)+7L)*512*512*4
    }
    fun info(tool:String)=JSONObject().put("tool",tool).put("engine","dab-v1").put("defaults",defaults(tool))
        .put("modes",JSONObject(modes)).put("sensors",JSONObject(sensors)).put("channels",JSONObject(channels))
        .put("pointFormat","[x,y,pressure,timeMs,tilt,rotation]；可省略后四项；时间非递减，最长180000ms")
        .put("ranges",JSONObject("""{"spacing":[0.02,2],"flow":[0,1],"scatter":[0,2],"count":[1,64],"jitter":[0,360],"airbrushRate":[0,120],"tip.ratio":[0.05,1],"tip.angle":[-360,360],"tip.hardness":[0,1],"texture.strength":[0,1],"texture.scale":[0.1,16],"smoothing.window":[2,64],"smoothing.strength":[0,1],"smoothing.delay":[0,128],"curvePoints":[2,16],"resourceEdge":[1,512]}"""))
        .put("limits",JSONObject().put("samples",MAX_SAMPLES).put("dabs",MAX_DABS).put("particles",MAX_PARTICLES))
        .put("resourceMask","tip黑色成笔尖/白色透明；texture白色保留/黑色减弱；alpha参与蒙版")
}
