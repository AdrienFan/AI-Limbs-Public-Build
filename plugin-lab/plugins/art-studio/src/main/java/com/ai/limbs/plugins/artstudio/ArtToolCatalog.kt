package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** The page and Laner's read-only catalog share one inventory of toolbox slots. */
internal object ArtToolCatalog {
    val implemented = listOf(
        Triple("ink", "自由画笔", "✎"),
        Triple("pencil", "铅笔", "✏"),
        Triple("soft", "软笔", "◌"),
        Triple("spray", "喷枪", "☷"),
        Triple("eraser", "橡皮擦", "▱"),
        Triple("mirror", "多重画笔", "⇄"),
        Triple("dyna", "动态画笔", "⌁"),
        Triple("calligraphy", "斜头书法笔（栅格）", "✒"),
        Triple("line", "直线", "╱"),
        Triple("rectangle", "矩形", "□"),
        Triple("ellipse", "椭圆", "○"),
        Triple("polygon", "多边形", "⬠"),
        Triple("polyline", "折线", "⌁"),
        Triple("bezier", "三次贝塞尔曲线（栅格）", "∿"),
        Triple("sampler", "颜色取样", "◉"),
        Triple("fill", "连续区域填充", "▨"),
        Triple("gradient", "渐变", "◩"),
        Triple("select", "矩形选区", "▣"),
        Triple("select_ellipse", "椭圆选区", "◯"),
        Triple("select_polygon", "多边形选区", "⬡"),
        Triple("select_freehand", "自由套索选区", "〰"),
        Triple("crop", "裁剪画布", "⛶"),
        Triple("move", "移动图层", "✥"),
        Triple("transform", "图层变换", "⤡"),
        Triple("pan", "平移画布", "✋"),
        Triple("zoom", "缩放画布", "⌕"),
        Triple("measure", "测量距离", "⌁")
    )

    data class PendingTool(
        val id: String, val label: String, val glyph: String, val source: String
    )

    // Each entry names a distinct Krita factory. A disabled slot must not route
    // touches to a superficially similar raster tool: that would silently change
    // artwork when the user expected an unimplemented vector or selection tool.
    val pending = listOf(
        PendingTool("shape_select", "形状选择", "↖",
            "plugins/tools/defaulttool/defaulttool/DefaultToolFactory.cpp"),
        PendingTool("svg_text", "SVG 文字", "T",
            "plugins/tools/svgtexttool/SvgTextToolFactory.cpp"),
        PendingTool("vector_freehand", "矢量徒手路径", "〽",
            "plugins/tools/basictools/kis_tool_pencil.h"),
        PendingTool("vector_bezier", "可编辑贝塞尔路径", "⌁",
            "plugins/tools/basictools/kis_tool_path.h"),
        PendingTool("vector_calligraphy", "矢量书法笔", "✒",
            "plugins/tools/karbonplugins/tools/CalligraphyTool/KarbonCalligraphyToolFactory.cpp"),
        PendingTool("reference_images", "参考图像", "▧",
            "plugins/tools/defaulttool/referenceimagestool/ToolReferenceImages.h"),
        PendingTool("assistant", "绘画辅助尺规", "⌖",
            "plugins/assistants/Assistants/assistant_tool.cc"),
        PendingTool("smart_patch", "智能修补", "✚",
            "plugins/tools/tool_smart_patch/kis_tool_smart_patch.h"),
        PendingTool("colorize_mask", "上色蒙版编辑", "▦",
            "plugins/tools/tool_lazybrush/kis_tool_lazy_brush.h"),
        PendingTool("enclose_fill", "围合填充", "⬟",
            "plugins/tools/tool_enclose_and_fill/KisToolEncloseAndFillFactory.h"),
        PendingTool("comic_panel", "漫画分格编辑", "▤",
            "plugins/tools/tool_knife/KisToolKnife.h"),
        PendingTool("select_bezier", "贝塞尔曲线选区", "♧",
            "plugins/tools/selectiontools/kis_tool_select_path.h"),
        PendingTool("select_contiguous", "连续区域选区", "◈",
            "plugins/tools/selectiontools/kis_tool_select_contiguous.h"),
        PendingTool("select_similar", "相似色选区", "◎",
            "plugins/tools/selectiontools/kis_tool_select_similar.h"),
        PendingTool("select_magnetic", "磁性套索选区", "⊙",
            "plugins/tools/selectiontools/KisToolSelectMagnetic.h")
    )

    fun describe(): JSONObject {
        val tools = JSONArray()
        implemented.forEach { (id, label, _) ->
            tools.put(JSONObject().put("id", id).put("label", label)
                .put("implemented", true).put("status", "basic"))
        }
        pending.forEach { item ->
            tools.put(JSONObject().put("id", item.id).put("label", item.label)
                .put("implemented", false).put("status", "planned")
                .put("source", item.source))
        }
        return JSONObject().put("tools", tools)
    }
}
