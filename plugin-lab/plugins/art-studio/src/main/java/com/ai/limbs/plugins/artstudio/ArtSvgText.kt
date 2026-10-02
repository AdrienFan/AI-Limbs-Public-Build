package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Strict, local SVG text profile. Unsupported markup is rejected rather than silently rasterized. */
internal object ArtSvgText {
    private val properties = setOf("font-family", "font-size", "font-weight", "font-style", "fill", "fill-opacity",
        "stroke", "stroke-width", "stroke-opacity", "letter-spacing", "word-spacing", "baseline-shift", "text-decoration",
        "font-feature-settings", "direction", "unicode-bidi", "writing-mode", "text-orientation", "text-anchor", "white-space",
        "inline-size", "line-height", "shape-inside", "shape-padding")
    private val positioning = setOf("x", "y", "dx", "dy", "rotate", "textLength", "lengthAdjust")
    private val numbers = Regex("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    fun info(): JSONObject = JSONObject().put("profile", "AI_LIMBS_SVG_TEXT_1")
        .put("elements", JSONArray(listOf("svg", "defs", "path", "rect", "ellipse", "text", "tspan", "textPath")))
        .put("properties", JSONArray(properties.toList())).put("positioning", JSONArray(positioning.toList()))
        .put("limits", "128KiB source, 1024 nodes, depth32, 4096 UTF-16 units; local # references")
        .put("unsupported", JSONArray(listOf("external resources", "stylesheet selectors", "animation", "filters", "paint servers/gradients",
            "text transform matrices", "multiple flow regions", "nested textLength", "SVG unicode-bidi embed/isolate/override", "SVG font embedding")))
        .put("completeSvgStandard", false)
    fun parse(source: String, base: JSONObject, resolveFont: (String, Int?, Boolean?, String) -> String): JSONObject {
        require(source.toByteArray(Charsets.UTF_8).size in 1..131072) { "SVG 源码最大 128 KiB" }
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "SVG 不允许 DTD 或实体声明" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> error("SVG 不允许外部实体") } }
        val root = builder.parse(InputSource(StringReader(source))).documentElement
        require(root.localName in setOf("svg", "text")) { "源码需要 svg 或 text 根元素" }
        var nodes = 0
        val ids = mutableMapOf<String, Element>()
        fun inspect(e: Element, depth: Int) {
            require(++nodes <= 1024 && depth <= 32)
            require(e.namespaceURI == null || e.namespaceURI == "http://www.w3.org/2000/svg") { "未知 SVG 命名空间" }
            require(e.localName in setOf("svg", "defs", "path", "rect", "ellipse", "text", "tspan", "textPath")) { "尚未支持 SVG 元素：${e.localName}" }
            if (e.hasAttribute("id")) { val id = e.getAttribute("id"); require(id.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) && ids.put(id, e) == null) }
            for (i in 0 until e.attributes.length) {
                val a = e.attributes.item(i); val name = a.localName ?: a.nodeName
                if (a.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
                if (a.namespaceURI == "http://www.w3.org/XML/1998/namespace") { require(name in setOf("space", "lang")); continue }
                if (a.namespaceURI != null) require(a.namespaceURI == "http://www.w3.org/1999/xlink" && name == "href")
                val geometry = when (e.localName) {
                    "svg" -> setOf("width", "height", "viewBox", "version", "preserveAspectRatio")
                    "path" -> setOf("d", "fill-rule")
                    "rect" -> setOf("x", "y", "width", "height", "rx", "ry")
                    "ellipse" -> setOf("cx", "cy", "rx", "ry")
                    "textPath" -> setOf("href", "startOffset", "method", "spacing", "side")
                    else -> emptySet()
                }
                require(name in properties || (name in positioning && e.localName in setOf("text","tspan","textPath")) || name in geometry || name in setOf("id", "style")) { "尚未支持 SVG 属性：$name" }
            }
            for (i in 0 until e.childNodes.length) {
                val n = e.childNodes.item(i)
                if (n is Element) inspect(n, depth + 1)
                else require(n.nodeType in setOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.COMMENT_NODE)) { "SVG 不允许处理指令" }
            }
        }
        inspect(root, 0)
        fun declarations(e: Element): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (k in properties) if (e.hasAttribute(k)) result[k] = e.getAttribute(k)
            if (e.hasAttribute("style")) for (declaration in e.getAttribute("style").split(';').filter { it.isNotBlank() }) {
                val parts = declaration.split(':', limit = 2); require(parts.size == 2 && parts[0].trim() in properties)
                result[parts[0].trim()] = parts[1].trim()
            }
            return result
        }
        val viewport = if (root.localName == "svg" && root.hasAttribute("viewBox")) list(root.getAttribute("viewBox"), 4)
            else listOf(0.0, 0.0, length(root.getAttribute("width").ifBlank { base.optInt("boxWidth", 640).toString() }, 48.0, 640.0),
                length(root.getAttribute("height").ifBlank { "480" }, 48.0, 480.0))
        require(viewport[2] in 1.0..16384.0 && viewport[3] in 1.0..16384.0)
        if(root.hasAttribute("viewBox"))for((key,index) in listOf("width" to 2,"height" to 3)) if(root.hasAttribute(key))
            require(kotlin.math.abs(length(root.getAttribute(key),48.0,viewport[index])-viewport[index])<.001) {"SVG 源编辑使用 1:1 文档单位；width/height 需与 viewBox 尺寸一致"}
        if (root.hasAttribute("preserveAspectRatio")) require(root.getAttribute("preserveAspectRatio") == "none") { "当前源码按 viewBox 文档单位编辑；preserveAspectRatio 仅支持 none" }
        // The cache uses document units; svg width/height do not rescale viewBox implicitly.
        val chunks = JSONArray(); val spans = JSONArray(); val content = StringBuilder()
        fun referenced(value: String): Element {
            val name = value.removePrefix("url(").removeSuffix(")").trim().trim('"', '\'')
            require(name.startsWith('#')) { "SVG 只接受本地 # 引用" }
            return ids[name.substring(1)] ?: error("SVG 引用不存在：$name")
        }
        fun geometry(e: Element): String = when (e.localName) {
            "path" -> e.getAttribute("d").also { ArtSvgPath.commands(it) }
            "rect" -> {
                require(!e.hasAttribute("rx") && !e.hasAttribute("ry")) { "圆角形状请用 path 定义" }
                val x = length(e.getAttribute("x").ifBlank { "0" }, 48.0, viewport[2]); val y = length(e.getAttribute("y").ifBlank { "0" }, 48.0, viewport[3])
                val w = length(e.getAttribute("width"), 48.0, viewport[2]); val h = length(e.getAttribute("height"), 48.0, viewport[3]); require(w > 0 && h > 0)
                "M$x $y H${x+w} V${y+h} H$x Z"
            }
            "ellipse" -> {
                val x = length(e.getAttribute("cx").ifBlank { "0" }, 48.0, viewport[2]); val y = length(e.getAttribute("cy").ifBlank { "0" }, 48.0, viewport[3])
                val a = length(e.getAttribute("rx"), 48.0, viewport[2]); val b = length(e.getAttribute("ry"), 48.0, viewport[3]); require(a > 0 && b > 0)
                "M${x-a} $y A$a $b 0 1 0 ${x+a} $y A$a $b 0 1 0 ${x-a} $y Z"
            }
            else -> error("文字几何引用必须是 path/rect/ellipse")
        }
        fun styles(e: Element, inherited: Map<String, String>): Map<String,String> {
            val local=declarations(e).toMutableMap()
            if(e.hasAttributeNS("http://www.w3.org/XML/1998/namespace","space")) local["white-space"]=
                if(e.getAttributeNS("http://www.w3.org/XML/1998/namespace","space")=="preserve") "pre" else "normal"
            return inherited+local
        }
        fun textStyle(css: Map<String, String>, inherited: JSONObject): JSONObject {
            val result = JSONObject(inherited.toString())
            val size = css["font-size"]?.let { length(it, inherited.optDouble("fontSize", 48.0), viewport[2]) } ?: inherited.optDouble("fontSize", 48.0)
            result.put("fontSize", size)
            val weight = css["font-weight"]?.let { if (it == "normal") 400 else if (it == "bold") 700 else it.toInt() }
            val italic = css["font-style"]?.let { require(it in setOf("normal", "italic")); it == "italic" }
            if (css.containsKey("font-family") || weight != null || italic != null)
                result.put("fontId", resolveFont(css["font-family"].orEmpty().trim().trim('"', '\''), weight, italic, result.getString("fontId")))
            for ((svg, key) in listOf("fill" to "color", "stroke" to "strokeColor")) if (css.containsKey(svg)) result.put(key, svgColor(css.getValue(svg)))
            for ((svg, key) in listOf("stroke-width" to "strokeWidth", "letter-spacing" to "letterSpacing", "word-spacing" to "wordSpacing", "baseline-shift" to "baselineShift")) {
                css[svg]?.let { value -> result.put(key, when { value == "normal" || value == "baseline" -> 0.0; value == "super" -> size * .4; value == "sub" -> -size * .2; else -> length(value, size, size) }) }
            }
            for ((svg, key) in listOf("fill-opacity" to "color", "stroke-opacity" to "strokeColor")) css[svg]?.let { value ->
                val opacity = value.toDouble(); require(opacity in 0.0..1.0)
                val c = result.getString(key); val alpha = (c.substring(1,3).toInt(16) * opacity).toInt()
                result.put(key, "#" + alpha.toString(16).padStart(2,'0') + c.substring(3))
            }
            css["text-decoration"]?.let { val words = it.split(Regex("\\s+")); require(words.all { w -> w in setOf("none", "underline", "line-through") }); result.put("underline", "underline" in words).put("strike", "line-through" in words) }
            css["font-feature-settings"]?.let { value ->
                result.put("fontFeatures", if (value == "normal") "" else value.split(',').joinToString(",") { setting ->
                    val m = Regex("[\"']([A-Za-z0-9]{4})[\"']\\s+(on|off|[0-9]{1,3})").matchEntire(setting.trim()) ?: error("font-feature-settings 无效")
                    m.groupValues[1] + "=" + when (m.groupValues[2]) { "on" -> "1"; "off" -> "0"; else -> m.groupValues[2] }
                })
            }
            return ArtTextSpec.style(result)
        }
        fun walk(e: Element, inheritedCss: Map<String, String>, inheritedStyle: JSONObject, inheritedGeometry: JSONObject, ancestors: List<Pair<Int, Map<String, List<Double>>>>, block: Int) {
            require(e.localName in setOf("text", "tspan", "textPath")) { "文字内部只允许 tspan/textPath" }
            val css = styles(e, inheritedCss); val style = textStyle(declarations(e), inheritedStyle)
            e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang").takeIf { it.isNotEmpty() }?.let { style.put("language", it) }
            val currentPositions = positioning.filter { e.hasAttribute(it) && it !in setOf("textLength", "lengthAdjust") }.associateWith { key ->
                if (key == "rotate") list(e.getAttribute(key), 4096) else lengths(e.getAttribute(key), style.getDouble("fontSize"), if (key in setOf("x", "dx")) viewport[2] else viewport[3])
            }
            val chain = ancestors + (content.codePointCount(0, content.length) to currentPositions)
            val writing = css["writing-mode"] ?: base.optString("writingMode", "horizontal-tb")
            require(writing in ArtTextSpec.writingModes)
            val direction = css["direction"] ?: base.optString("direction", "auto"); require(direction in setOf("auto", "ltr", "rtl"))
            val bidi = css["unicode-bidi"] ?: "normal"; require(bidi in setOf("normal", "plaintext")) { "SVG unicode-bidi 目前支持 normal/plaintext；显式嵌入可在原文中使用 Unicode 双向控制符" }
            if(e.localName!="text") {
                require(css["writing-mode"]==inheritedCss["writing-mode"] && css["direction"]==inheritedCss["direction"] && css["unicode-bidi"]==inheritedCss["unicode-bidi"] && css["text-orientation"]==inheritedCss["text-orientation"]) { "文字方向和 unicode-bidi 需在根 text 设置" }
            }
            val white = css["white-space"] ?: if (e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "space") == "preserve") "pre" else "normal"
            require(white in setOf("normal", "pre", "pre-wrap"))
            val anchor = css["text-anchor"] ?: "start"; require(anchor in setOf("start", "middle", "end"))
            require((css["text-orientation"] ?: "mixed") in setOf("mixed","upright","sideways")) {"SVG text-orientation 无效"}
            val path = if (e.localName == "textPath") {
                require(!e.hasAttribute("method") || e.getAttribute("method") == "align")
                require(!e.hasAttribute("spacing") || e.getAttribute("spacing") == "exact")
                require(!e.hasAttribute("side") || e.getAttribute("side") == "left")
                val href = if (e.hasAttribute("href")) e.getAttribute("href") else e.getAttributeNS("http://www.w3.org/1999/xlink", "href")
                val value = JSONObject().put("d", geometry(referenced(href)))
                val start = e.getAttribute("startOffset").ifBlank { "0" }
                if (start.endsWith('%')) value.put("startPercent", start.dropLast(1).toDouble().also { require(it.isFinite() && it in -100.0..100.0) })
                else value.put("startOffset", length(start, style.getDouble("fontSize"), viewport[2]))
                value
            } else inheritedGeometry.optJSONObject("textPath")
            require(e.localName == "text" || !declarations(e).containsKey("shape-inside")) { "shape-inside 仅支持根 text" }
            val inside = declarations(e)["shape-inside"]?.let { value -> JSONObject().put("d", geometry(referenced(value))).put("fillRule",referenced(value).getAttribute("fill-rule").ifBlank {"nonzero"}).put("padding", css["shape-padding"]?.let { length(it, style.getDouble("fontSize"), viewport[2]) } ?: 0.0) } ?: inheritedGeometry.optJSONObject("shapeInside")
            val rootStart = content.length
            for (n in 0 until e.childNodes.length) {
                val child = e.childNodes.item(n)
                if (child is Element) {
                    val inherited=JSONObject();path?.let { inherited.put("textPath",it) };inside?.let { inherited.put("shapeInside",it) }
                    walk(child, css, style, inherited, chain, block)
                } else if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                    var value = child.nodeValue
                    if (white == "normal") {
                        value = value.replace(Regex("\\s+"), " ")
                        if(content.isEmpty() || content.last()==' ')value=value.trimStart(' ')
                    }
                    if (value.isEmpty()) continue
                    val from = content.length; val cpStart = content.codePointCount(0, from)
                    content.append(value)
                    require(content.length <= 4096 && chunks.length() < 256)
                    val positions = JSONObject()
                    for (key in listOf("x", "y", "dx", "dy", "rotate")) {
                        val values = JSONArray(); var offset = 0
                        while (offset < value.length) {
                            val index = cpStart + value.codePointCount(0, offset)
                            var item: Double? = null
                            for ((origin, attributes) in chain) {
                                val valuesAt = attributes[key] ?: continue
                                val at = index - origin
                                if (at in valuesAt.indices) item = valuesAt[at]
                                else if (key == "rotate" && at >= 0 && valuesAt.isNotEmpty()) item = valuesAt.last()
                            }
                            values.put(item ?: JSONObject.NULL); offset += Character.charCount(value.codePointAt(offset))
                        }
                        positions.put(key, values)
                    }
                    val chunk = JSONObject().put("start", from).put("end", content.length).put("positions", positions)
                        .put("block",block).put("style", style).put("writingMode", writing).put("direction", direction).put("unicodeBidi", bidi)
                        .put("textOrientation", css["text-orientation"] ?: "mixed").put("anchor", anchor)
                    css["inline-size"]?.let { chunk.put("inlineSize", length(it, style.getDouble("fontSize"), viewport[2]).also {size->require(size in 1.0..16384.0)}) }
                    css["line-height"]?.let { v -> chunk.put("lineSpacing", if (v == "normal") 1.2 else v.toDouble().also { require(it in 1.0..3.0) }) }
                    path?.let { chunk.put("textPath", it) }; inside?.let { chunk.put("shapeInside", it) }
                    chunks.put(chunk); spans.put(JSONObject(style.toString()).put("start", from).put("end", content.length))
                }
            }
            if (e.hasAttribute("textLength")) {
                require(chunks.length() > 0 && chunks.getJSONObject(chunks.length()-1).getInt("start") == rootStart) { "textLength 仅支持单个连续文本段，尚不支持嵌套 textLength" }
                val adjust = e.getAttribute("lengthAdjust").ifBlank { "spacing" }; require(adjust in setOf("spacing", "spacingAndGlyphs"))
                chunks.getJSONObject(chunks.length()-1).put("textLength", length(e.getAttribute("textLength"), style.getDouble("fontSize"), viewport[2])).put("lengthAdjust", adjust)
            } else require(!e.hasAttribute("lengthAdjust"))
        }
        val rootCss = declarations(root)
        if (root.localName == "text") walk(root, emptyMap(), base, JSONObject(), emptyList(),0)
        else for (i in 0 until root.childNodes.length) {
            val child = root.childNodes.item(i)
            if (child is Element) when (child.localName) {
                "text" -> walk(child, rootCss, textStyle(rootCss, base), JSONObject(), emptyList(),i)
                "defs" -> Unit
                else -> error("SVG 根的可见对象只支持 text；几何请放入 defs")
            }
        }
        require(chunks.length() > 0 && spans.length() <= 128 && content.isNotBlank())
        return JSONObject(base.toString()).put("content", content.toString()).put("spans", spans)
            .put("sourceMode", "svg").put("svgSource", source).put("svgChunks", chunks).put("svgViewBox", JSONArray(viewport))
    }
    private fun list(value: String, max: Int): List<Double> {
        val matches = numbers.findAll(value).toList(); var end = 0
        matches.forEach { require(value.substring(end, it.range.first).all { c -> c.isWhitespace() || c == ',' }); end = it.range.last+1 }
        require(value.substring(end).all { it.isWhitespace() || it == ',' } && matches.size in 1..max)
        return matches.map { it.value.toDouble().also { v -> require(v.isFinite() && kotlin.math.abs(v) <= 1000000) } }
    }
    private fun lengths(value: String, em: Double, reference: Double): List<Double> = value.trim().split(Regex("[\\s,]+"))
        .map { length(it, em, reference) }.also { require(it.size in 1..4096) }
    private fun length(value: String, em: Double, reference: Double): Double {
        val m = Regex("([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)(px|pt|pc|mm|cm|in|em|%)?").matchEntire(value.trim()) ?: error("SVG 长度无效：$value")
        val number = m.groupValues[1].toDouble(); val scale = when (m.groupValues[2]) {
            "pt" -> 96.0/72; "pc" -> 16.0; "mm" -> 96.0/25.4; "cm" -> 96.0/2.54; "in" -> 96.0; "em" -> em; "%" -> reference/100; else -> 1.0
        }
        return (number*scale).also { require(it.isFinite() && kotlin.math.abs(it)<=1000000) }
    }
    private fun svgColor(value: String): String {
        val c = value.trim()
        return when {
            c == "none" -> "#00000000"
            c.matches(Regex("#[A-Fa-f0-9]{6}")) -> "#FF" + c.substring(1)
            c.matches(Regex("#[A-Fa-f0-9]{3}")) -> "#FF" + c.drop(1).map { "$it$it" }.joinToString("")
            c.matches(Regex("#[A-Fa-f0-9]{8}")) -> "#" + c.takeLast(2) + c.substring(1,7)
            else -> when (c) { "black" -> "#FF000000"; "white" -> "#FFFFFFFF"; "red" -> "#FFFF0000"; "blue" -> "#FF0000FF"; "green" -> "#FF008000"; "transparent" -> "#00000000"; else -> error("尚不支持 SVG 颜色：$c；请使用 #RRGGBB 或 #RRGGBBAA") }
        }
    }
}
