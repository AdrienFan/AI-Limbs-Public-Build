package com.ai.limbs.plugins.artstudio

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Font discovery is a catalog of readable files, not an assertion that every OEM entry is installed. */
internal object ArtSystemFontCatalog {
    data class Source(val file: File, val index: Int, val weight: Int, val slant: Int,
        val settings: String, val family: String, val languages: String) {
        // Keep the published identity format, including axis order, for existing text projects.
        val id: String get() = file.absolutePath + "#" + index + "#" + weight + "#" + slant + "#" + settings
    }
    data class Unavailable(val name: String, val reason: String)
    data class Inventory(val available: List<Source>, val unavailable: List<Unavailable>)

    fun read(configuration: File, directory: File): Inventory {
        require(configuration.isFile && configuration.canRead()) { "系统字体配置不可读取：$configuration" }
        val source = configuration.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
            "系统字体配置不允许 DTD 或实体声明"
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> error("系统字体配置不允许外部实体") }
        }
        val root = builder.parse(org.xml.sax.InputSource(java.io.StringReader(source))).documentElement
        require(root.tagName == "familyset") { "系统字体配置根元素须为 familyset" }
        val installed = mutableListOf<Source>()
        val unavailable = mutableListOf<Unavailable>()
        val fontDirectory = directory.canonicalFile
        val families = root.getElementsByTagName("family")
        for (familyIndex in 0 until families.length) {
            val family = families.item(familyIndex) as Element
            val languages = family.getAttribute("lang")
            val familyName = family.getAttribute("name").ifBlank { "family-" + (familyIndex + 1) }
            val fonts = family.getElementsByTagName("font")
            for (fontIndex in 0 until fonts.length) {
                val font = fonts.item(fontIndex) as Element
                val weight = if (font.hasAttribute("weight")) font.getAttribute("weight").toInt() else 400
                val index = if (font.hasAttribute("index")) font.getAttribute("index").toInt() else 0
                val slant = if (font.getAttribute("style") == "italic") 1 else 0
                val filename = StringBuilder()
                val settings = mutableListOf<String>()
                for (childIndex in 0 until font.childNodes.length) {
                    val child = font.childNodes.item(childIndex)
                    when (child.nodeType) {
                        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> filename.append(child.nodeValue)
                        Node.ELEMENT_NODE -> {
                            val axis = child as Element
                            require(axis.tagName == "axis") { "未知系统字体配置元素：${axis.tagName}" }
                            val tag = axis.getAttribute("tag")
                            val value = axis.getAttribute("stylevalue").toFloat()
                            require(tag.length == 4 && value.isFinite()) { "系统字体轴设置无效" }
                            settings.add("'" + tag + "' " + value)
                        }
                    }
                }
                val name = filename.toString().trim()
                require(name.isNotEmpty()) { "系统字体声明缺少文件名" }
                val file = File(fontDirectory, name).canonicalFile
                require(file.parentFile == fontDirectory) { "系统字体声明越出字体目录：$name" }
                // Missing OEM declarations are not selectable faces. Do not stop discovery of
                // unrelated readable fonts or substitute a different font for this declaration.
                when {
                    !file.isFile -> unavailable.add(Unavailable(name, "not_installed"))
                    !file.canRead() -> unavailable.add(Unavailable(name, "not_readable"))
                    else -> installed.add(Source(file, index, weight, slant,
                        settings.joinToString(), familyName, languages))
                }
            }
        }
        return Inventory(installed, unavailable.distinct())
    }
}
