package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.graphics.fonts.Font
import android.icu.lang.UScript
import org.json.JSONArray
import org.json.JSONObject
import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

/** Shape whole script/style runs, reorder bidi runs, then position explicit glyphs. */
internal object ArtTextLayout {
    private data class Glyph(val id: Int, val font: Font, val style: JSONObject, val cluster: Int,
        var inline: Float, var normal: Float, var angle: Float = 0f, var scale: Float = 1f, var origin: Float = inline)
    private data class Line(val glyphs: MutableList<Glyph>, val advance: Float, val extent: Float, val rtl: Boolean, val ascent: Float)
    private data class Placed(val glyph: Glyph, val x: Float, val y: Float)
    private data class Segment(val start: Int, val end: Int, val level: Byte, val script: Int, val style: JSONObject, val upright: Boolean)
    private class Layout(private val source: JSONObject) {
        val content = source.getString("content")
        private val spans = source.getJSONArray("spans")
        private val baseStyle=ArtTextSpec.style(source)
        private val styles=(0 until spans.length()).map {ArtTextSpec.style(spans.getJSONObject(it),baseStyle)}
        private val styleKeys=java.util.IdentityHashMap<JSONObject,String>()
        private fun styleKey(style:JSONObject)=styleKeys[style] ?: style.toString().also {styleKeys[style]=it}
        val writing = source.getString("writingMode")
        val vertical = writing != "horizontal-tb"
        private var shapes = 0
        private fun style(index: Int): JSONObject {
            for (n in 0 until spans.length()) { val span = spans.getJSONObject(n); if(index in span.getInt("start") until span.getInt("end")) return styles[n] }
            return baseStyle
        }
        fun shape(start: Int, end: Int, direction: String = source.getString("direction"), overrideStyle: JSONObject? = null,
            writingMode: String = writing, orientation: String = source.getString("textOrientation"), bidiMode: String = "normal"): Line {
            if (start == end) return Line(mutableListOf(), 0f, source.getDouble("fontSize").toFloat(), direction == "rtl", source.getDouble("fontSize").toFloat()*.8f)
            require(++shapes <= 8192) { "文字排版工作量过大，请减少段数或增加文字框宽度" }
            val value = content.substring(start, end)
            val flag = when (if(bidiMode=="plaintext") "auto" else direction) { "rtl" -> Bidi.DIRECTION_RIGHT_TO_LEFT; "ltr" -> Bidi.DIRECTION_LEFT_TO_RIGHT; else -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT }
            val bidi = Bidi(value, flag)
            val force = bidiMode in setOf("bidi-override", "isolate-override")
            val uprightScripts = setOf(UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA, UScript.HANGUL, UScript.BOPOMOFO)
            val scripts = IntArray(value.length)
            var index = 0
            while(index < value.length) {
                val cp = value.codePointAt(index); val script = UScript.getScript(cp)
                repeat(Character.charCount(cp)) { scripts[index+it] = script }; index += Character.charCount(cp)
            }
            for(i in scripts.indices) if(scripts[i] in setOf(UScript.COMMON,UScript.INHERITED)) {
                val previous = (i-1 downTo 0).firstOrNull { scripts[it] !in setOf(UScript.COMMON,UScript.INHERITED) }
                val next = (i+1 until scripts.size).firstOrNull { scripts[it] !in setOf(UScript.COMMON,UScript.INHERITED) }
                scripts[i] = previous?.let { scripts[it] } ?: next?.let { scripts[it] } ?: UScript.COMMON
            }
            val segments = mutableListOf<Segment>(); var at = 0
            while(at < value.length) {
                val s = overrideStyle ?: style(start+at); val key = styleKey(s)
                val level = if(force) (if(direction == "rtl") 1 else 0).toByte() else bidi.getLevelAt(at).toByte()
                val script = scripts[at]
                val upright = writingMode != "horizontal-tb" && (orientation == "upright" || orientation == "mixed" && script in uprightScripts)
                var limit = at+Character.charCount(value.codePointAt(at))
                while(limit < value.length && scripts[limit] == script &&
                    (force || bidi.getLevelAt(limit).toByte() == level) && styleKey(overrideStyle ?: style(start+limit)) == key)
                    limit += Character.charCount(value.codePointAt(limit))
                segments.add(Segment(start+at,start+limit,level,script,s,upright)); at = limit
            }
            val visual: Array<Any> = segments.map { it as Any }.toTypedArray()
            Bidi.reorderVisually(segments.map { it.level }.toByteArray(),0,visual,0,visual.size)
            val glyphs = mutableListOf<Glyph>(); var cursor = 0f; var extent = 0f; var ascent = 0f; var descent = 0f
            for(item in visual) {
                val segment = item as Segment; val s = segment.style; val face = ArtText.face(s.getString("fontId"))
                val size = s.getDouble("fontSize").toFloat()
                val dir = if(segment.upright) "ttb" else if(segment.level.toInt() and 1 == 1) "rtl" else "ltr"
                val raw = ArtTextShaper.shape(face.font,value,segment.start-start,segment.end-start,size,dir,
                    UScript.getShortName(segment.script),s.getString("language"),s.getString("fontFeatures"))
                if(glyphs.isNotEmpty())cursor+=s.getDouble("letterSpacing").toFloat()
                var penX = 0f; var penY = 0f; var previousCluster = -1
                val metrics = Paint.FontMetrics(); face.font.getMetrics(Paint().apply { textSize=size },metrics)
                val shift=s.getDouble("baselineShift").toFloat()
                ascent=maxOf(ascent,-metrics.ascent+shift);descent=maxOf(descent,metrics.descent-shift)
                extent=maxOf(extent,ascent+descent)
                for(n in raw.indices step 6) {
                    val cluster = start+raw[n+1].toInt()
                    if(previousCluster >= 0 && cluster != previousCluster) {
                        val spacing = s.getDouble("letterSpacing").toFloat()
                        if(segment.upright) penY += spacing else penX += spacing
                    }
                    val inline = if(segment.upright) penY+raw[n+5] else penX+raw[n+4]
                    val normal = if(segment.upright) penX+raw[n+4] else penY+raw[n+5]
                    glyphs.add(Glyph(raw[n].toInt(),face.font,s,cluster,cursor+inline,normal,
                        if(writingMode != "horizontal-tb" && !segment.upright) 90f else 0f,origin=cursor+if(segment.upright)penY else penX))
                    penX += raw[n+2]; penY += raw[n+3]
                    if(cluster != previousCluster && content.codePointAt(cluster) in setOf(32,9,0x3000)) {
                        val spacing = s.getDouble("wordSpacing").toFloat()
                        if(segment.upright) penY += spacing else penX += spacing
                    }
                    previousCluster = cluster
                }
                val advance = if(segment.upright) penY else penX
                require(advance.isFinite() && advance >= 0 && glyphs.size <= 32768) { "字距令文字倒退或字形数量过多" }
                cursor += advance
            }
            return Line(glyphs,cursor,extent.coerceAtLeast(1f),!bidi.baseIsLeftToRight(),ascent)
        }
        fun fit(start: Int,end: Int,width: Float, direction: String = source.getString("direction")): Pair<Int,Line> {
            require(width.isFinite() && width > 0)
            val locale = Locale.forLanguageTag(source.getString("language"))
            val characters = BreakIterator.getCharacterInstance(locale).apply { setText(content) }
            val boundaries = mutableListOf<Int>(); var next = characters.following(start)
            while(next != BreakIterator.DONE && next <= end) { boundaries.add(next); next = characters.next() }
            require(boundaries.isNotEmpty()) { "文字分段不是完整字符簇" }
            var lo = 0; var hi = boundaries.size-1; var best = -1
            while(lo <= hi) {
                val mid = (lo+hi)/2; val line = shape(start,boundaries[mid],direction)
                if(line.advance <= width) { best=mid;lo=mid+1 } else hi=mid-1
            }
            require(best >= 0) { "文字区域太窄，无法容纳一个完整字符簇" }
            var limit = boundaries[best]
            if(limit < end) {
                val words = BreakIterator.getLineInstance(locale).apply { setText(content) }
                val word = if(words.isBoundary(limit)) limit else words.preceding(limit)
                if(word > start) limit=word
            }
            return limit to shape(start,limit,direction)
        }
        fun aligned(line: Line,width: Float): Float = when(source.getString("align")) {
            "center" -> (width-line.advance)/2; "right" -> width-line.advance
            "start" -> if(line.rtl) width-line.advance else 0f
            "end" -> if(line.rtl) 0f else width-line.advance
            else -> 0f
        }
        fun normal(): MutableList<Placed> {
            val result = mutableListOf<Placed>()
            val path = source.optJSONObject("textPath")?.let { geometry(it) }
            val inside = source.optJSONObject("shapeInside")?.let { geometry(it) }
            val box = source.getInt("boxWidth").toFloat()
            var cross = 0f; var start = 0; var lines=0
            if(path != null) {
                require(!content.contains('\n')) { "路径文字需为单段" }
                val line = shape(0,content.length)
                val parameters=JSONObject(source.getJSONObject("textPath").toString());val measure=PathMeasure(path,false)
                parameters.put("startOffset",parameters.getDouble("startOffset")+aligned(line,measure.length))
                placeOnPath(result,line,path,parameters); return result
            }
            val bounds = RectF(); inside?.computeBounds(bounds,true)
            val padding = source.optJSONObject("shapeInside")?.optDouble("padding",0.0)?.toFloat() ?: 0f
            if(inside != null) cross = if(vertical) { if(writing == "vertical-rl") bounds.right-padding else bounds.left+padding } else bounds.top+padding
            while(start <= content.length) {
                val end = content.indexOf('\n',start).let { if(it < 0) content.length else it }
                if(start == end) { cross += source.getDouble("fontSize").toFloat()*source.getDouble("lineSpacing").toFloat()*if(writing == "vertical-rl") -1 else 1 }
                val paragraphDirection=if(source.getString("direction")=="auto") {
                    if(Bidi(content.substring(start,end),Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()) "ltr" else "rtl"
                } else source.getString("direction")
                var cursor = start
                while(cursor < end) {
                    require(++lines <= 512) { "文字最多 512 行／列" }
                    val maxSize = maxOf(source.getDouble("fontSize"), (0 until spans.length()).map { spans.getJSONObject(it).getDouble("fontSize") }.maxOrNull() ?: 0.0).toFloat()
                    val extent = maxSize*1.5f
                    val intervals = if(inside != null) intervals(inside,bounds,cross,extent,padding,vertical,writing == "vertical-rl") else listOf(0f to box)
                    if(intervals.isEmpty()) {
                        cross += extent*source.getDouble("lineSpacing").toFloat()*if(writing == "vertical-rl") -1 else 1
                        require(if(vertical) cross in (bounds.left..bounds.right) else cross <= bounds.bottom) { "形状内空间不足，文字未提交" }
                        continue
                    }
                    var usedExtent=if(inside==null)0f else extent
                    for((a,b) in intervals) {
                        if(cursor >= end) break
                        if(inside!=null) {
                            val characters=BreakIterator.getCharacterInstance(Locale.forLanguageTag(source.getString("language"))).apply {setText(content)}
                            val next=characters.following(cursor)
                            if(next<=end&&shape(cursor,next,paragraphDirection).advance>b-a)continue
                        }
                        val (limit,line) = fit(cursor,end,b-a,paragraphDirection)
                        usedExtent=maxOf(usedExtent,line.extent)
                        val offset = a+aligned(line,b-a)
                        for(glyph in line.glyphs) {
                            val shift = glyph.style.getDouble("baselineShift").toFloat()
                            if(vertical) {
                                val baseline = cross + if(writing == "vertical-rl") -usedExtent/2 else usedExtent/2
                                result.add(Placed(glyph,baseline+(if(glyph.angle == 90f) -glyph.normal else glyph.normal)+shift,offset+glyph.inline))
                            } else result.add(Placed(glyph,offset+glyph.inline,cross+line.ascent+glyph.normal-shift))
                        }
                        cursor=limit
                    }
                    cross += usedExtent*source.getDouble("lineSpacing").toFloat()*if(writing == "vertical-rl") -1 else 1
                    if(inside != null && cursor < end) require(if(vertical) cross in (bounds.left..bounds.right) else cross <= bounds.bottom-padding) { "形状内空间不足，文字未提交" }
                }
                if(end == content.length) break
                start=end+1
            }
            return result
        }
        fun svg(): MutableList<Placed> {
            val result=mutableListOf<Placed>();val chunks=source.getJSONArray("svgChunks")
            var x=0f;var y=0f;var atChunk=0
            while(atChunk<chunks.length()) {
                val first=chunks.getJSONObject(atChunk);val group=mutableListOf(first);var next=atChunk+1
                fun groupKey(c:JSONObject)=listOf("block","writingMode","direction","textOrientation","anchor","textPath","shapeInside","inlineSize","lineSpacing").joinToString("|"){c.opt(it)?.toString().orEmpty()}
                while(next<chunks.length()) {
                    val c=chunks.getJSONObject(next)
                    if(groupKey(c)!=groupKey(first)||first.has("textLength")||c.has("textLength"))break
                    group.add(c);next++
                }
                atChunk=next
                val from=first.getInt("start");val end=group.last().getInt("end");val mode=first.getString("writingMode")
                fun position(key:String,cluster:Int):Float? {
                    val c=group.firstOrNull {cluster in it.getInt("start") until it.getInt("end")} ?: return null
                    val cp=content.codePointCount(c.getInt("start"),cluster);val values=c.getJSONObject("positions").getJSONArray(key)
                    return if(cp<values.length()&&!values.isNull(cp))values.getDouble(cp).toFloat() else null
                }
                x=position("x",from)?:x;y=position("y",from)?:y
                if(first.has("inlineSize")||first.has("shapeInside")) {
                    require(!first.has("textPath"))
                    for(c in group) for(key in listOf("x","y","dx","dy","rotate")) {
                        val values=c.getJSONObject("positions").getJSONArray(key)
                        for(i in 0 until values.length()) require(values.isNull(i)||(key in setOf("x","y")&&c===first&&i==0)) { "SVG 自动换行区不支持逐字定位或旋转" }
                    }
                    val local=JSONObject(source.toString()).put("content",content.substring(from,end))
                    val localSpans=JSONArray()
                    for(c in group)localSpans.put(JSONObject(c.getJSONObject("style").toString()).put("start",c.getInt("start")-from).put("end",c.getInt("end")-from))
                    local.put("spans",localSpans).put("sourceMode","rich")
                    local.remove("svgChunks");local.remove("svgSource");local.remove("textPath");local.remove("shapeInside")
                    local.put("writingMode",mode).put("direction",first.getString("direction")).put("textOrientation",first.getString("textOrientation"))
                        .put("align",when(first.getString("anchor")){"middle"->"center";"end"->"end";else->"start"})
                        .put("boxWidth",first.optDouble("inlineSize",source.getInt("boxWidth").toDouble()).toInt()).put("lineSpacing",first.optDouble("lineSpacing",1.2))
                    if(first.has("shapeInside"))local.put("shapeInside",first.getJSONObject("shapeInside"))
                    val placed=Layout(ArtTextSpec.normalize(local)).normal();placed.forEach {result.add(Placed(it.glyph,x+it.x,y+it.y))}
                    continue
                }
                require(!content.substring(from,end).contains('\n')) { "SVG 换行需 inline-size/shape-inside" }
                val line=shape(from,end,first.getString("direction"),null,mode,first.getString("textOrientation"),first.getString("unicodeBidi"))
                var target=line.advance
                if(first.has("textLength")) {
                    target=first.getDouble("textLength").toFloat();require(target>0&&line.advance>0)
                    if(first.getString("lengthAdjust")=="spacingAndGlyphs")line.glyphs.forEach {it.inline*=target/line.advance;it.origin*=target/line.advance;it.scale=target/line.advance}
                    else {
                        val clusters=line.glyphs.map {it.cluster}.distinct();require(clusters.size>1||abs(target-line.advance)<.01f) { "单字符簇不能用 spacing 改变 textLength" }
                        line.glyphs.forEach {val delta=(target-line.advance)*clusters.indexOf(it.cluster)/(clusters.size-1).coerceAtLeast(1);it.inline+=delta;it.origin+=delta}
                    }
                }
                val anchor=when(first.getString("anchor")){"middle"->-target/2;"end"->if(line.rtl)0f else -target;else->if(line.rtl)-target else 0f}
                val offsets=mutableMapOf<Int,Pair<Float,Float>>();var sx=0f;var sy=0f
                for(cluster in line.glyphs.map {it.cluster}.distinct().sorted()) {
                    val g=line.glyphs.first {it.cluster==cluster}
                    if(cluster!=from) {
                        position("x",cluster)?.let {sx=it-x-(if(mode=="horizontal-tb")g.inline else 0f)}
                        position("y",cluster)?.let {sy=it-y-(if(mode!="horizontal-tb")g.inline else 0f)}
                    }
                    sx+=position("dx",cluster)?:0f;sy+=position("dy",cluster)?:0f
                    offsets[cluster]=sx to sy
                    // A positioning boundary cannot split a ligature/combining cluster.
                    val last=line.glyphs.map {it.cluster}.filter {it>cluster}.minOrNull() ?: end
                    var cp=cluster+Character.charCount(content.codePointAt(cluster))
                    while(cp<last){for(key in listOf("x","y","dx","dy","rotate"))require(position(key,cp)==null||(key=="rotate"&&position(key,cp)==position(key,cluster))) { "逐字定位不可切开塑形字符簇；可关闭 liga 或在完整字符簇边界定位" };cp+=Character.charCount(content.codePointAt(cp))}
                }
                if(first.has("textPath")) {
                    require(mode=="horizontal-tb") { "textPath 当前只支持横排行进方向" }
                    val p=first.getJSONObject("textPath");val path=geometry(p);val measure=PathMeasure(path,false)
                    val args=JSONObject(p.toString());if(p.has("startPercent"))args.put("startOffset",measure.length*p.getDouble("startPercent")/100)
                    args.put("startOffset",args.optDouble("startOffset",0.0)+anchor)
                    line.glyphs.forEach {g->val offset=offsets.getValue(g.cluster);g.inline+=offset.first;g.origin+=offset.first;g.normal+=offset.second;g.angle=position("rotate",g.cluster)?:0f}
                    placeOnPath(result,line.copy(advance=target),path,args);continue
                }
                for(g in line.glyphs) {
                    val shift=offsets.getValue(g.cluster);g.angle+=position("rotate",g.cluster)?:0f
                    val baseline=g.style.getDouble("baselineShift").toFloat()
                    result.add(if(mode=="horizontal-tb")Placed(g,x+anchor+g.inline+shift.first,y+g.normal+shift.second-baseline)
                        else Placed(g,x+(if(g.angle==90f)-g.normal else g.normal)+shift.first+baseline,y+anchor+g.inline+shift.second))
                }
                if(mode=="horizontal-tb"){x+=target+sx;y+=sy}else{y+=target+sy;x+=sx}
            }
            return result
        }
    }
    private fun geometry(p:JSONObject):Path {
        val path=if(p.has("d"))ArtSvgPath.path(p.getString("d")) else {
            val shape=ArtShapes.normalize(p.getJSONObject("shape"));ArtShapes.path(shape).also {it.transform(ArtShapes.matrix(shape.getJSONArray("matrix")))}
        }
        path.fillType=if(p.optString("fillRule","nonzero")=="evenodd")Path.FillType.EVEN_ODD else Path.FillType.WINDING
        return path
    }
    private fun intervals(path:Path,b:RectF,cross:Float,extent:Float,padding:Float,vertical:Boolean,reverse:Boolean):List<Pair<Float,Float>> {
        // Project every missing region rectangle across the entire line band. This retains holes
        // and concave boundaries without assuming three sample lines cover the glyph rectangle.
        require(b.width()<=16384&&b.height()<=16384&&listOf(b.left,b.top,b.right,b.bottom).all {it.isFinite()&&abs(it)<=100000})
        val normal=if(reverse)cross-extent else cross
        val minimum=if(vertical)b.left else b.top;val maximum=if(vertical)b.right else b.bottom
        if(normal<minimum+padding||normal+extent>maximum-padding)return emptyList()
        val scaled=Path(path);scaled.transform(Matrix().apply {setScale(4f,4f)})
        val region=Region();region.setPath(scaled,Region(floor(b.left*4).toInt()-1,floor(b.top*4).toInt()-1,ceil(b.right*4).toInt()+1,ceil(b.bottom*4).toInt()+1))
        val from=if(vertical)b.top else b.left;val to=if(vertical)b.bottom else b.right
        val first=floor(from*4).toInt();val last=ceil(to*4).toInt()
        val near=floor(normal*4).toInt();val far=ceil((normal+extent)*4).toInt()
        val strip=if(vertical)Region(near,first,far,last) else Region(first,near,last,far)
        strip.op(region,Region.Op.DIFFERENCE)
        val blocked=mutableListOf<Pair<Int,Int>>();val iterator=RegionIterator(strip);val rectangle=Rect()
        while(iterator.next(rectangle))blocked.add(if(vertical)rectangle.top to rectangle.bottom else rectangle.left to rectangle.right)
        val gaps=mutableListOf<Pair<Float,Float>>();var cursor=first
        for((a,z) in blocked.sortedBy {it.first}) {
            if(a>cursor&&((a-cursor)/4f)>padding*2+1)gaps.add(cursor/4f+padding to a/4f-padding)
            cursor=maxOf(cursor,z)
        }
        if(last>cursor&&((last-cursor)/4f)>padding*2+1)gaps.add(cursor/4f+padding to last/4f-padding)
        return gaps
    }
    private fun placeOnPath(result:MutableList<Placed>,line:Line,path:Path,p:JSONObject) {
        val measure=PathMeasure(path,false);require(measure.length>0);val length=measure.length
        require(!measure.nextContour()) { "路径文字仅支持单个连续子路径" };measure.setPath(path,false)
        val offset=p.optDouble("startOffset",0.0).toFloat();val normal=p.optDouble("normalOffset",0.0).toFloat()
        require(offset>=0&&offset+line.advance<=length+.01f&&line.glyphs.all {offset+it.origin in 0f..length}) { "路径长度不足或 startOffset 超出路径；文字未提交" }
        val anchors=line.glyphs.groupBy {it.cluster}.mapValues {(_,glyphs)->glyphs.minOf {it.origin}}
        val ordered=anchors.values.distinct().sorted()
        for(g in line.glyphs) {
            val origin=anchors.getValue(g.cluster)
            val end=ordered.firstOrNull {it>origin} ?: line.advance
            val half=(end-origin).coerceAtLeast(0f)/2
            val point=FloatArray(2);val tangent=FloatArray(2)
            require(measure.getPosTan(offset+origin+half,point,tangent))
            val pathAngle=atan2(tangent[1],tangent[0]);val total=pathAngle+Math.toRadians(g.angle.toDouble()).toFloat()
            val displacement=normal-g.style.getDouble("baselineShift").toFloat()
            val u=g.inline-origin-half;val v=g.normal
            result.add(Placed(g.copy(angle=Math.toDegrees(total.toDouble()).toFloat()),
                point[0]+cos(total)*u-sin(total)*v-tangent[1]*displacement,
                point[1]+sin(total)*u+cos(total)*v+tangent[0]*displacement))
        }
    }
    private fun paint(g:Glyph)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=g.style.getDouble("fontSize").toFloat();color=Color.parseColor(g.style.getString("color"))
    }
    fun render(source:JSONObject,extraBytes:Long):Bitmap {
        ArtImagePolicy.requireBytes(extraBytes+8L*1024*1024,"文字塑形预检")
        val layout=Layout(source);val placed=if(source.getString("sourceMode")=="svg")layout.svg() else layout.normal()
        if(source.getString("sourceMode")=="svg") {
            val viewBox=source.getJSONArray("svgViewBox")
            for(i in placed.indices)placed[i]=placed[i].copy(x=placed[i].x-viewBox.getDouble(0).toFloat(),y=placed[i].y-viewBox.getDouble(1).toFloat())
        }
        require(placed.isNotEmpty()&&placed.size<=32768)
        val bounds=RectF();var initialized=false
        for(p in placed) {
            val box=RectF();p.glyph.font.getGlyphBounds(p.glyph.id,paint(p.glyph),box)
            val fontPaint=paint(p.glyph)
            if(p.glyph.style.getBoolean("underline")||p.glyph.style.getBoolean("strike")) {
                val advance=p.glyph.font.getGlyphBounds(p.glyph.id,fontPaint,RectF())
                box.union(0f,-fontPaint.textSize*.35f,advance.coerceAtLeast(1f),fontPaint.textSize*.18f)
            }
            val stroke=p.glyph.style.getDouble("strokeWidth").toFloat()/2
            box.inset(-stroke-2,-stroke-2)
            val matrix=Matrix().apply {setScale(p.glyph.scale,1f);postRotate(p.glyph.angle);postTranslate(p.x,p.y)}
            matrix.mapRect(box)
            if(!initialized){bounds.set(box);initialized=true}else bounds.union(box)
        }
        if(source.getString("sourceMode")!="svg"&&!source.has("textPath")&&!source.has("shapeInside")) {
            if(layout.vertical)bounds.union(0f,0f,1f,source.getInt("boxWidth").toFloat()) else bounds.union(0f,0f,source.getInt("boxWidth").toFloat(),1f)
        }
        val left=floor(bounds.left);val top=floor(bounds.top);val width=ceil(bounds.right-left).toInt().coerceAtLeast(1);val height=ceil(bounds.bottom-top).toInt().coerceAtLeast(1)
        ArtImagePolicy.requireDimensions(width,height);ArtImagePolicy.requireBytes(width.toLong()*height*32+extraBytes+placed.size*256L,"文字排版与渲染")
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {density=Bitmap.DENSITY_NONE}
        try {
            val canvas=Canvas(bitmap);canvas.translate(-left,-top)
            for(p in placed) {
                val g=p.glyph;val paint=paint(g);val save=canvas.save();canvas.translate(p.x,p.y);canvas.rotate(g.angle);canvas.scale(g.scale,1f)
                val ids=intArrayOf(g.id);val coordinates=floatArrayOf(0f,0f)
                if(g.style.getDouble("strokeWidth")>0&&Color.alpha(Color.parseColor(g.style.getString("strokeColor")))>0) {
                    paint.color=Color.parseColor(g.style.getString("strokeColor"));paint.style=Paint.Style.STROKE;paint.strokeWidth=g.style.getDouble("strokeWidth").toFloat()
                    canvas.drawGlyphs(ids,0,coordinates,0,1,g.font,paint)
                }
                paint.color=Color.parseColor(g.style.getString("color"));paint.style=Paint.Style.FILL
                canvas.drawGlyphs(ids,0,coordinates,0,1,g.font,paint)
                if(g.style.getBoolean("underline")||g.style.getBoolean("strike")) {
                    val box=RectF();val advance=g.font.getGlyphBounds(g.id,paint,box);paint.strokeWidth=maxOf(1f,paint.textSize/16)
                    if(g.style.getBoolean("underline"))canvas.drawLine(0f,paint.textSize*.12f,advance,paint.textSize*.12f,paint)
                    if(g.style.getBoolean("strike"))canvas.drawLine(0f,-paint.textSize*.3f,advance,-paint.textSize*.3f,paint)
                }
                canvas.restoreToCount(save)
            }
            source.put("cacheWidth",width).put("cacheHeight",height).put("cacheOriginX",left.toDouble()).put("cacheOriginY",top.toDouble())
                .put("layoutGlyphs",placed.size)
            return bitmap
        } catch(error:Throwable){bitmap.recycle();throw error}
    }
}
