package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fonts are constructed explicitly; rendering never resolves Android's default Typeface. */
internal object ArtText {
    const val NOTICE = "支持富文本、显式字体 OpenType 塑形、双向文字、竖排、路径／形状内文字及 SVG 文字源码；SVG 为已声明的文字配置档，并非完整 SVG 标准。显示使用可编辑源数据的 PNG 缓存。"
    val available: Boolean get() = Build.VERSION.SDK_INT >= 31
    private fun requireAvailable() = require(available) { "文字需要 Android 12 或以上的显式字形绘制接口" }
    internal class Face(val id: String, val file: File, val index: Int, val weight: Int,
        val slant: Int, val settings: String, val family: String, val languages: String) {
        val font: Font by lazy {
            val builder = Font.Builder(file).setTtcIndex(index).setWeight(weight).setSlant(slant)
            if (settings.isNotEmpty()) builder.setFontVariationSettings(settings)
            builder.build()
        }
        val cmap by lazy { Cmap(font.buffer.duplicate().order(ByteOrder.BIG_ENDIAN), font.ttcIndex) }
        fun describe() = JSONObject().put("id", id).put("family", family).put("languages", languages)
            .put("weight", weight).put("italic", slant == 1)
            .put("label", file.name + " · " + index + " · " + weight + if (slant == 1) " 斜体" else "")
        fun contains(cp: Int) = cmap.glyph(cp) != 0
    }
    private val faces: List<Face> by lazy {
        requireAvailable()
        configuredFonts().distinctBy { it.id }.sortedWith(compareBy<Face>(
            { kotlin.math.abs(it.weight - 400) }, { it.slant },
            { if (it.languages.split(',').any { language -> language.startsWith("zh-Hans") }) 0 else 1 }, { it.id }))
    }
    private fun configuredFonts(): List<Face> {
        val configuration = File("/system/etc/fonts.xml")
        require(configuration.isFile && configuration.canRead()) { "系统字体配置不可读取：/system/etc/fonts.xml" }
        val result = mutableListOf<Face>()
        configuration.inputStream().use { input ->
            val parser = Xml.newPullParser(); parser.setInput(input, "UTF-8")
            var languages = ""; var family = ""; var familyNumber = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "family") {
                    languages = parser.getAttributeValue(null, "lang").orEmpty()
                    family = parser.getAttributeValue(null, "name").orEmpty()
                    familyNumber++
                }
                if (event == XmlPullParser.START_TAG && parser.name == "font") {
                    val fontDepth = parser.depth
                    val weight = parser.getAttributeValue(null, "weight")?.toInt() ?: 400
                    val index = parser.getAttributeValue(null, "index")?.toInt() ?: 0
                    val slant = if (parser.getAttributeValue(null, "style") == "italic") 1 else 0
                    val axes = mutableListOf<FontVariationAxis>(); val filename = StringBuilder()
                    var child = parser.next()
                    while (!(child == XmlPullParser.END_TAG && parser.depth == fontDepth)) {
                        require(child != XmlPullParser.END_DOCUMENT) { "系统字体配置未闭合" }
                        if (child == XmlPullParser.TEXT && parser.depth == fontDepth) filename.append(parser.text)
                        if (child == XmlPullParser.START_TAG && parser.name == "axis") {
                            val tag = requireNotNull(parser.getAttributeValue(null, "tag"))
                            val value = requireNotNull(parser.getAttributeValue(null, "stylevalue")).toFloat()
                            require(tag.length == 4 && value.isFinite()); axes.add(FontVariationAxis(tag, value))
                        }
                        child = parser.next()
                    }
                    val name = filename.toString().trim(); require(name.isNotEmpty())
                    val file = File("/system/fonts", name).canonicalFile
                    require(file.parentFile == File("/system/fonts").canonicalFile && file.isFile && file.canRead()) { "系统声明字体不可读取：$name" }
                    val settings = axes.joinToString { "'" + it.tag + "' " + it.styleValue }
                    val key = file.absolutePath + "#" + index + "#" + weight + "#" + slant + "#" + settings
                    result.add(Face(key, file, index, weight, slant, settings,
                        family.ifBlank { "family-$familyNumber" }, languages))
                }
                if (event == XmlPullParser.END_TAG && parser.name == "family") { languages = ""; family = "" }
                event = parser.next()
            }
        }
        return result
    }
    fun fonts(): JSONObject {
        if (!available) return JSONObject().put("available", false).put("reason", "文字需要 Android 12 或以上").put("fonts", JSONArray())
        return JSONObject().put("available", faces.isNotEmpty()).put("defaultFontId", defaultFontId())
            .put("fonts", JSONArray(faces.map { it.describe() })).put("notice", NOTICE)
            .put("renderMode", "raster_cache").put("editable", true).put("layout", "harfbuzz_bidi_svg_text_profile")
            .put("automaticFontSubstitution", false)
    }
    fun defaultFontId(): String {
        requireAvailable()
        return faces.firstOrNull { it.languages.split(',').any { lang -> lang.startsWith("zh") } && it.contains(0x4E2D) && it.contains(65) }?.id
            ?: error("系统没有可用的默认中英文字体，请明确指定 fontId")
    }
    internal fun face(id: String): Face = faces.firstOrNull { it.id == id }
        ?: error("原字体在当前设备不可用，请明确选择当前字体后重新编辑")
    private fun resolveFont(family: String, weight: Int?, italic: Boolean?, current: String): String {
        val original = face(current)
        require(!family.contains(',')) { "SVG font-family 需明确指定一个 text.fonts 返回的 family" }
        val key = family.ifBlank { original.family }
        return faces.firstOrNull { it.family == key && it.weight == (weight ?: original.weight) && it.slant == (italic?.let { v -> if(v) 1 else 0 } ?: original.slant) }?.id
            ?: error("指定字体族／字重／斜体未安装：$key；请从 text.fonts 明确选择")
    }
    fun normalize(p: JSONObject): JSONObject = ArtTextSpec.normalize(JSONObject(p.toString()).also {
        if (!it.has("fontId")) it.put("fontId", defaultFontId())
    })
    fun prepare(p: JSONObject): JSONObject {
        val input = JSONObject(p.toString())
        if (!input.has("fontId")) input.put("fontId", defaultFontId())
        if (input.optString("sourceMode", if(input.has("svgSource")) "svg" else "plain") == "svg") {
            val parsed = ArtSvgText.parse(input.getString("svgSource"), ArtTextSpec.style(input), ::resolveFont)
            for (key in listOf("boxWidth", "lineSpacing", "align", "writingMode", "direction", "textOrientation"))
                if (input.has(key)) parsed.put(key, input.get(key))
            return normalize(parsed)
        }
        input.remove("svgSource"); input.remove("svgChunks"); input.remove("svgViewBox")
        return normalize(input)
    }
    fun svgSource(p: JSONObject): String {
        if(p.optString("sourceMode")=="svg")return p.getString("svgSource")
        fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        fun rgba(value:String)="#"+value.substring(3)+value.substring(1,3)
        fun attributes(style:JSONObject):String {
            val f=face(style.getString("fontId"))
            return "font-family=\"${escape(f.family)}\" font-size=\"${style.getDouble("fontSize")}\" font-weight=\"${f.weight}\" font-style=\"${if(f.slant==1) "italic" else "normal"}\" fill=\"${rgba(style.getString("color"))}\" "+
                "stroke=\"${rgba(style.getString("strokeColor"))}\" stroke-width=\"${style.getDouble("strokeWidth")}\" letter-spacing=\"${style.getDouble("letterSpacing")}\" word-spacing=\"${style.getDouble("wordSpacing")}\" baseline-shift=\"${style.getDouble("baselineShift")}\" "+
                "text-decoration=\"${listOfNotNull(if(style.getBoolean("underline")) "underline" else null,if(style.getBoolean("strike")) "line-through" else null).joinToString(" ").ifBlank {"none"}}\" xml:lang=\"${escape(style.getString("language"))}\""+
                if(style.getString("fontFeatures").isBlank()) "" else " font-feature-settings=\""+style.getString("fontFeatures").split(',').joinToString(",") {s->val pair=s.trim().split('=');"'${pair[0]}' ${pair[1]}"}+"\""
        }
        val normalized=normalize(p);val content=normalized.getString("content");val spans=normalized.getJSONArray("spans");val text=StringBuilder();var start=0
        for(i in 0 until spans.length()) {
            val span=spans.getJSONObject(i);text.append(escape(content.substring(start,span.getInt("start"))))
            text.append("<tspan ${attributes(span)}>${escape(content.substring(span.getInt("start"),span.getInt("end")))}</tspan>");start=span.getInt("end")
        }
        text.append(escape(content.substring(start)))
        val geometry=normalized.optJSONObject("textPath") ?: normalized.optJSONObject("shapeInside")
        require(geometry==null||geometry.has("d")) {"形状快照转换 SVG 需要显式提供路径 d；可以在源码编辑器另写 defs"}
        val defs=geometry?.let {"<defs><path id=\"text-geometry\" d=\"${escape(it.getString("d"))}\"/></defs>"}.orEmpty()
        val writing=normalized.getString("writingMode");val direction=normalized.getString("direction")
        val anchor=when(normalized.getString("align")){"center"->"middle";"right","end"->"end";else->"start"}
        val inline=if(geometry==null) "inline-size=\"${normalized.getInt("boxWidth")}\"" else if(normalized.has("shapeInside")) "shape-inside=\"url(#text-geometry)\" shape-padding=\"${geometry.getDouble("padding")}\"" else ""
        val body=if(normalized.has("textPath")) {
            require(!content.contains('\n')&&geometry!!.getDouble("normalOffset")==0.0) {"路径源码转换需单段且 normalOffset=0"}
            "<textPath href=\"#text-geometry\" startOffset=\"${geometry.getDouble("startOffset")}\">$text</textPath>"
        } else text.toString()
        return "<svg xmlns=\"http://www.w3.org/2000/svg\"><!-- AI Limbs SVG text profile -->$defs<text ${attributes(normalized)} writing-mode=\"$writing\" direction=\"$direction\" text-orientation=\"${normalized.getString("textOrientation")}\" text-anchor=\"$anchor\" white-space=\"pre-wrap\" line-height=\"${normalized.getDouble("lineSpacing")}\" $inline>$body</text></svg>"
    }
    fun anchor(layer: JSONObject): Pair<Double,Double> {
        val text=layer.getJSONObject("text")
        val offset=floatArrayOf(-text.optDouble("cacheOriginX",0.0).toFloat(),-text.optDouble("cacheOriginY",0.0).toFloat())
        ArtShapes.localMatrix(layer).mapPoints(offset);return offset[0].toDouble() to offset[1].toDouble()
    }
    fun render(text: JSONObject, extraBytes: Long): Bitmap {
        requireAvailable()
        return ArtTextLayout.render(text, extraBytes)
    }
    /** OpenType Unicode cmap format 4/12. No shaping substitutions or guessed glyphs. */
    internal class Cmap(private val data: ByteBuffer, index: Int) {
        private fun u16(p: Int) = data.getShort(p).toInt() and 0xFFFF
        private fun u32(p: Int): Int {
            val value = data.getInt(p).toLong() and 0xFFFFFFFFL
            require(value <= Int.MAX_VALUE) { "字体表偏移过大" }
            return value.toInt()
        }
        private fun check(p: Int, n: Int) { require(p >= 0 && n >= 0 && p.toLong() + n <= data.limit()) { "字体表越界" } }
        private val table: Int
        private val length: Int
        private val format: Int
        init {
            check(0, 12)
            val sfnt = if (data.getInt(0) == 0x74746366) {
                require(index >= 0 && index < u32(8)); check(12, (index + 1) * 4)
                u32(12 + index * 4)
            } else 0
            check(sfnt, 12)
            val count = u16(sfnt + 4)
            check(sfnt + 12, count * 16)
            var cmap = -1
            var cmapLength = 0
            for (n in 0 until count) {
                val record = sfnt + 12 + n * 16
                if (data.getInt(record) == 0x636D6170) { cmap = u32(record + 8); cmapLength = u32(record + 12) }
            }
            check(cmap, cmapLength); require(cmapLength >= 4) { "字体没有 Unicode cmap" }
            val records = u16(cmap + 2)
            require(4L + records * 8 <= cmapLength)
            val candidates = mutableListOf<Pair<Int, Int>>()
            for (n in 0 until records) {
                val p = cmap + 4 + n * 8
                val platform = u16(p); val encoding = u16(p + 2)
                val relative = u32(p + 4)
                require(relative.toLong() + 2 <= cmapLength)
                val at = cmap + relative
                val f = u16(at)
                if ((platform == 0 || (platform == 3 && encoding in setOf(1, 10))) && f in setOf(4, 12))
                    candidates.add(f to at)
            }
            val selected = candidates.maxByOrNull { it.first } ?: error("字体缺少支持的 Unicode cmap")
            format = selected.first; table = selected.second
            check(table, if (format == 12) 16 else 14)
            length = if (format == 12) u32(table + 4) else u16(table + 2)
            require(table.toLong() + length <= cmap.toLong() + cmapLength)
            check(table, length)
            require(length >= 16)
            if (format == 12) require(16L + u32(table + 12).toLong() * 12 <= length)
            else require(16L + u16(table + 6).toLong() / 2 * 8 <= length && u16(table + 6) % 2 == 0)
        }
        fun glyph(cp: Int): Int {
            if (format == 12) {
                var low = 0; var high = u32(table + 12) - 1
                while (low <= high) {
                    val mid = (low + high) ushr 1; val p = table + 16 + mid * 12
                    val first = u32(p); val last = u32(p + 4)
                    if (cp < first) high = mid - 1 else if (cp > last) low = mid + 1
                    else return (u32(p + 8).toLong() + cp - first).also { require(it in 0L..65535L) }.toInt()
                }
                return 0
            }
            if (cp > 65535) return 0
            val count = u16(table + 6) / 2
            val ends = table + 14; val starts = ends + count * 2 + 2
            val deltas = starts + count * 2; val offsets = deltas + count * 2
            var low = 0; var high = count
            while (low < high) {
                val mid = (low + high) ushr 1
                if (cp > u16(ends + mid * 2)) low = mid + 1 else high = mid
            }
            val n = low
            if (n == count) return 0
            val start = u16(starts + n * 2)
            if (cp < start) return 0
            val delta = u16(deltas + n * 2); val relative = u16(offsets + n * 2)
            if (relative == 0) return (cp + delta) and 65535
            val address = offsets + n * 2 + relative + (cp - start) * 2
            require(address >= table && address.toLong() + 2 <= table.toLong() + length)
            val glyph = u16(address)
            return if (glyph == 0) 0 else (glyph + delta) and 65535
        }
    }
}
