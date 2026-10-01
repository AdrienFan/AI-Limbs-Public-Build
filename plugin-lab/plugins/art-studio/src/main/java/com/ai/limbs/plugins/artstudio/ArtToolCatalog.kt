package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** The page and Laner's read-only catalog share one inventory of toolbox slots. */
internal object ArtToolCatalog {
    val implemented get() = listOf(
        Triple("smart_patch","智能修补（局部纹理）","✚"),
        Triple("assistant","绘画辅助尺规（基础）","⌖"),
        Triple("reference_images","参考图像","▧"),
        Triple("vector_calligraphy", "矢量书法笔", "✒"),
        Triple("vector_bezier", "可编辑贝塞尔路径", "⌁"),
        Triple("vector_freehand", "矢量徒手路径", "〽"),
        Triple("shape_select", "形状选择（基础矢量）", "↖"),
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
    ) + if (ArtText.available) listOf(Triple("svg_text", "文字（基础可编辑）", "T")) else emptyList()

    data class PendingTool(
        val id: String, val label: String, val glyph: String, val source: String
    )

    // Pending entries name Krita factories; advanced text is the remaining portion
    // of the factory whose basic editable subset is now implemented. A disabled slot must not route
    // touches to a superficially similar raster tool: that would silently change
    // artwork when the user expected an unimplemented vector or selection tool.
    val pending get() = listOf(
        PendingTool("svg_text_advanced", "SVG 文字高级排版", "T",
            "plugins/tools/svgtexttool/SvgTextToolFactory.cpp"),
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
    ) + if (!ArtText.available) listOf(PendingTool("svg_text", "基础文字需要 Android 12+", "T",
        "plugins/tools/svgtexttool/SvgTextToolFactory.cpp")) else emptyList()


    fun usage(id: String): String = when (id) {
        "ink", "pencil", "soft", "spray", "eraser", "line", "polyline" ->
            "在画布上绘制；颜色、笔粗和不透明度使用右侧画笔面板的设置。"
        "select", "select_ellipse", "select_polygon", "select_freehand" ->
            "在画布上绘制选区；后续编辑作用于当前选区。"
        "crop" -> "拖动框出裁剪区域，松开后裁剪画布。"
        "move" -> "拖动移动当前图层；存在选区时移动选区内容。"
        "pan" -> "拖动画布调整显示位置，不改变作品。"
        "measure" -> "拖动测量画布中的距离。"
        else -> "双击工具打开参数浮窗；浮窗关闭后仍保留当前工具和参数。"
    }

    fun describe(): JSONObject {
        val tools = JSONArray()
        implemented.forEach { (id, label, _) ->
            tools.put(JSONObject().put("id", id).put("label", label)
                .put("implemented", true).put("status", "basic")
                .put("parameterWindow", JSONObject().put("gesture", "double-click").put("available", true)))
        }
        pending.forEach { item ->
            tools.put(JSONObject().put("id", item.id).put("label", item.label)
                .put("implemented", false).put("status", "planned")
                .put("source", item.source)
                .put("parameterWindow", JSONObject().put("gesture", "double-click").put("available", true)
                    .put("drawingEnabled", false)))
        }
        val zoomState = ArtStudioViewControl.state.value
        return JSONObject().put("tools", tools).put("textScope", ArtText.NOTICE)
            .put("parameterWindowCapability", "$ART_ID.view.tool_options")
            .put("smartPatch",ArtSmartPatch.info())
            .put("assistants",JSONObject().put("types",JSONObject(ArtAssistants.types))
                .put("pending",JSONArray(ArtAssistants.pending)).put("maxObjects",ArtAssistants.MAX)
                .put("coordinateSpace","document").put("exported",false)
                .put("supportedBrushTools",JSONArray(ArtAssistants.brushTools.toList())))
            .put("calligraphy",JSONObject().put("capability","$ART_ID.shape.calligraphy")
                .put("coordinateSpace","layer-local").put("timeUnit","milliseconds")
                .put("maxSamples",ArtCalligraphy.MAX_SAMPLES)
                .put("editable","普通封闭轮廓，可用形状选择和贝塞尔节点工具修改")
                .put("pending",JSONArray(listOf("followSelectedPath","tabletAngle","massAndDrag","presets"))))
            .put("zoomTool", JSONObject().put("mode", zoomState.zoomToolMode)
                .put("badge", zoomState.zoomToolBadge)
                .put("modeCapability", "$ART_ID.view.zoom_tool")
                .put("usage", "选中后再次点击图标切换放大/缩小；点击画布以该点为中心缩放。"))
    }
}
