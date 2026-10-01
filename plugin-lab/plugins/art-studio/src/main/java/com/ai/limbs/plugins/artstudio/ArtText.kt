package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Explicit glyph APIs do not resolve the uninitialized default Typeface in app_process. */
internal object ArtText {
    const val NOTICE = "基础横排文字：支持中文、预组合拉丁字母、日文和预组合韩文；复杂塑形、组合符号、双向文字、Emoji、富文本及 SVG 编辑待实现。"
    val available: Boolean get() = Build.VERSION.SDK_INT >= 31
    private fun requireAvailable() = require(available) { "基础文字需要 Android 12 或以上的显式字形绘制接口" }

    private data class Face(val id: String, val font: Font, val languages: String) {
        val cmap by lazy { Cmap(font.buffer.duplicate().order(ByteOrder.BIG_ENDIAN), font.ttcIndex) }
        fun describe() = JSONObject().put("id", id)
            .put("label", requireNotNull(font.file).name + " · " + font.ttcIndex +
                " · " + font.style.weight + if (font.style.slant == 1) " 斜体" else "")
    }
    private val faces: List<Face> by lazy {
        requireAvailable()
        configuredFonts().distinctBy { it.id }.sortedWith(compareBy<Face>(
            { kotlin.math.abs(it.font.style.weight - 400) },
            { it.font.style.slant },
            { if (it.languages.split(',').any { language -> language.startsWith("zh-Hans") }) 0 else 1 },
            { it.id }))
    }
    /** fonts.xml is Android's installed compatibility font configuration, not a process font map.
     * Select declared CJK families and construct Font directly, without initializing Typeface.
     */
    private fun configuredFonts(): List<Face> {
        val configuration = File("/system/etc/fonts.xml")
        require(configuration.isFile && configuration.canRead()) { "系统字体配置不可读取：/system/etc/fonts.xml" }
        val result = mutableListOf<Face>()
        configuration.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var languages = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "family")
                    languages = parser.getAttributeValue(null, "lang").orEmpty()
                if (event == XmlPullParser.START_TAG && parser.name == "font") {
                    val fontDepth = parser.depth
                    val weight = parser.getAttributeValue(null, "weight")?.toInt() ?: 400
                    val index = parser.getAttributeValue(null, "index")?.toInt() ?: 0
                    val slant = if (parser.getAttributeValue(null, "style") == "italic") 1 else 0
                    val axes = mutableListOf<FontVariationAxis>()
                    val filename = StringBuilder()
                    var child = parser.next()
                    while (!(child == XmlPullParser.END_TAG && parser.depth == fontDepth)) {
                        require(child != XmlPullParser.END_DOCUMENT) { "系统字体配置未闭合" }
                        if (child == XmlPullParser.TEXT && parser.depth == fontDepth)
                            filename.append(parser.text)
                        if (child == XmlPullParser.START_TAG && parser.name == "axis") {
                            val tag = requireNotNull(parser.getAttributeValue(null, "tag"))
                            val value = requireNotNull(parser.getAttributeValue(null, "stylevalue")).toFloat()
                            require(tag.length == 4 && value.isFinite()) { "系统字体变体配置无效" }
                            axes.add(FontVariationAxis(tag, value))
                        }
                        child = parser.next()
                    }
                    // This feature offers CJK families with Latin glyphs, not a language fallback chain.
                    if (languages.split(',').any { it.startsWith("zh") || it.startsWith("ja") || it.startsWith("ko") }) {
                        val name = filename.toString().trim()
                        require(name.isNotEmpty())
                        val file = File("/system/fonts", name).canonicalFile
                        require(file.parentFile == File("/system/fonts").canonicalFile && file.isFile && file.canRead()) {
                            "系统声明的中英文字体不可读取：" + name
                        }
                        val settings = axes.joinToString { "'" + it.tag + "' " + it.styleValue }
                        val builder = Font.Builder(file).setTtcIndex(index).setWeight(weight).setSlant(slant)
                        if (settings.isNotEmpty()) builder.setFontVariationSettings(settings)
                        val font = builder.build()
                        val key = file.absolutePath + "#" + index + "#" + weight + "#" + slant + "#" + settings
                        result.add(Face(key, font, languages))
                    }
                }
                if (event == XmlPullParser.END_TAG && parser.name == "family") languages = ""
                event = parser.next()
            }
        }
        return result
    }
    fun fonts(): JSONObject {
        if (!available) return JSONObject().put("available", false)
            .put("reason", "基础文字需要 Android 12 或以上").put("fonts", JSONArray())
        val chinese = faces.filter { it.cmap.glyph(0x4E2D) != 0 && it.cmap.glyph(65) != 0 }
        return JSONObject().put("available", chinese.isNotEmpty())
            .put("defaultFontId", chinese.firstOrNull()?.id ?: JSONObject.NULL)
            .put("fonts", JSONArray(chinese.map { it.describe() })).put("notice", NOTICE)
            .put("renderMode", "raster_cache").put("editable", true).put("layout", "basic_horizontal_no_shaping")
    }
    fun defaultFontId(): String {
        requireAvailable()
        return faces.firstOrNull { it.cmap.glyph(0x4E2D) != 0 && it.cmap.glyph(65) != 0 }?.id
            ?: error("系统没有可用的中英文字体")
    }

    /** Source parameters remain editable; PNG is only a portable render cache. */
    fun normalize(p: JSONObject): JSONObject {
        val content = p.getString("content").replace("\r\n", "\n").replace('\r', '\n')
        require(content.isNotBlank() && content.length <= 4096) { "文字需要 1–4096 个字符，且不能全为空白" }
        require(content.count { it == '\n' } < 128) { "文字最多 128 行" }
        val size = p.optDouble("fontSize", 48.0)
        val box = p.optInt("boxWidth", 640)
        val spacing = p.optDouble("lineSpacing", 1.2)
        val align = p.optString("align", "left")
        val color = p.optString("color", "#FF000000")
        require(size.isFinite() && size in 6.0..512.0) { "字号需要在 6–512 像素之间" }
        require(box in 1..16384 && spacing.isFinite() && spacing in 1.0..3.0) { "文字框宽度或行距无效" }
        require(align in setOf("left", "center", "right")) { "对齐方式需要为 left/center/right" }
        require(color.matches(Regex("#[A-Fa-f0-9]{8}"))) { "文字颜色需要为 #AARRGGBB" }
        val fontId = if (p.has("fontId")) p.getString("fontId") else defaultFontId()
        return JSONObject().put("content", content).put("fontId", fontId).put("fontSize", size)
            .put("boxWidth", box).put("lineSpacing", spacing).put("align", align).put("color", color)
    }

    fun render(text: JSONObject, extraBytes: Long): Bitmap {
        requireAvailable()
        val face = faces.firstOrNull { it.id == text.getString("fontId") }
            ?: error("原字体在当前设备不可用，请明确选择当前字体后重新编辑")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = text.getDouble("fontSize").toFloat()
            color = Color.parseColor(text.getString("color"))
        }
        val metrics = Paint.FontMetrics()
        face.font.getMetrics(paint, metrics)
        data class Glyph(val id: Int, val advance: Float, val bounds: RectF)
        val lines = mutableListOf(mutableListOf<Glyph>())
        val widths = mutableListOf(0f)
        val box = text.getInt("boxWidth").toFloat()
        val content = text.getString("content")
        var offset = 0
        while (offset < content.length) {
            val cp = Character.codePointAt(content, offset)
            offset += Character.charCount(cp)
            if (cp == 10) {
                lines.add(mutableListOf()); widths.add(0f); continue
            }
            val script = Character.UnicodeScript.of(cp)
            val category = Character.getType(cp)
            require(script in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.LATIN,
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.COMMON) &&
                category !in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt(), Character.CONTROL.toInt(),
                    Character.SURROGATE.toInt()) && cp !in 0x1F000..0x1FFFF &&
                cp !in 0x1100..0x11FF && cp !in 0xA960..0xA97F && cp !in 0xD7B0..0xD7FF) {
                "当前基础文字不支持此字符的排版 U+" + cp.toString(16).uppercase() + "；" + NOTICE
            }
            val glyphId = face.cmap.glyph(cp)
            require(glyphId != 0) { "所选字体缺少字符 U+" + cp.toString(16).uppercase() + "，请选择合适字体" }
            val bounds = RectF()
            val advance = face.font.getGlyphBounds(glyphId, paint, bounds)
            require(advance.isFinite() && advance >= 0f && advance <= box) { "文字框太窄，无法容纳所选字号" }
            if (widths.last() + advance > box && lines.last().isNotEmpty()) {
                lines.add(mutableListOf()); widths.add(0f)
            }
            require(lines.size <= 512) { "自动换行后的文字行数过多，请增加文字框宽度" }
            lines.last().add(Glyph(glyphId, advance, bounds))
            widths[widths.lastIndex] += advance
        }
        val step = (metrics.descent - metrics.ascent) * text.getDouble("lineSpacing").toFloat()
        var top = 0f
        var bottom = metrics.descent - metrics.ascent + (lines.size - 1) * step
        var left = 0f
        var right = box
        fun lineStart(i: Int) = when (text.getString("align")) {
            "center" -> (box - widths[i]) / 2
            "right" -> box - widths[i]
            else -> 0f
        }
        for (i in lines.indices) {
            var x = lineStart(i)
            val baseline = -metrics.ascent + i * step
            for (glyph in lines[i]) {
                left = minOf(left, x + glyph.bounds.left)
                right = maxOf(right, x + glyph.bounds.right)
                top = minOf(top, baseline + glyph.bounds.top)
                bottom = maxOf(bottom, baseline + glyph.bounds.bottom)
                x += glyph.advance
            }
        }
        val width = ceil((right - left).toDouble()).toInt().coerceAtLeast(1) + 2
        val height = ceil((bottom - top).toDouble()).toInt().coerceAtLeast(1) + 2
        ArtImagePolicy.requireDimensions(width, height)
        ArtImagePolicy.requireBytes(width.toLong() * height * 32 + extraBytes, "文字排版与渲染")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        try {
            val canvas = Canvas(bitmap)
            for (i in lines.indices) {
                if (lines[i].isEmpty()) continue
                val ids = IntArray(lines[i].size)
                val positions = FloatArray(ids.size * 2)
                var x = lineStart(i) - left + 1f
                for (g in ids.indices) {
                    ids[g] = lines[i][g].id
                    positions[g * 2] = x
                    positions[g * 2 + 1] = -metrics.ascent + i * step - top + 1f
                    x += lines[i][g].advance
                }
                canvas.drawGlyphs(ids, 0, positions, 0, ids.size, face.font, paint)
            }
            text.put("cacheWidth", width).put("cacheHeight", height)
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    /** OpenType Unicode cmap format 4/12. No shaping substitutions or guessed glyphs. */
    private class Cmap(private val data: ByteBuffer, index: Int) {
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
