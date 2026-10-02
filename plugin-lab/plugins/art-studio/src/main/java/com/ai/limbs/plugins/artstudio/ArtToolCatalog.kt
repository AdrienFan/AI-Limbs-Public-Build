package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** The page and Laner's read-only catalog share one inventory of toolbox slots. */
internal object ArtToolCatalog {
    val implemented get() = listOf(
        Triple("select_contiguous","连续区域选区（基础）","◈"),
        Triple("select_similar","相似色选区（基础）","◎"),
        Triple("select_magnetic","磁性套索选区（基础）","⊙"),
        Triple("comic_panel","漫画分格编辑（直边）","▤"),
        Triple("select_bezier","贝塞尔曲线选区（基础）","♧"),
        Triple("enclose_fill","围合填充（基础）","⬟"),
        Triple("colorize_mask","上色蒙版编辑（基础）","▦"),
        Triple("smart_patch","智能修补（局部纹理）","✚"),
        Triple("assistant","绘画辅助尺规","⌖"),
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
    ) + if (ArtText.available) listOf(Triple("svg_text", "文字", "T")) else emptyList()

    data class PendingTool(
        val id: String, val label: String, val glyph: String, val source: String
    )

    // Advanced text is part of the existing text tool's parameters, not another toolbox slot.
    val textAdvancedOptions = listOf("富文本", "SVG 文字源码编辑", "复杂塑形与双向文字", "竖排", "路径／形状内文字")

    // Keep the platform requirement visible on devices that cannot render basic text.
    val pending get() = if (!ArtText.available) listOf(PendingTool("svg_text",
        "文字需要 Android 12+", "T", "plugins/tools/svgtexttool/SvgTextToolFactory.cpp"))
        else emptyList()

    fun usage(id: String): String = when (id) {
        "ink", "pencil", "soft", "spray", "eraser", "line", "polyline" ->
            "在画布上绘制；颜色、笔粗和不透明度使用右侧画笔面板的设置。"
        "select", "select_ellipse", "select_polygon", "select_freehand" ->
            "在画布上绘制选区；后续编辑作用于当前选区。"
        "crop" -> "拖出裁剪框，拖内部或八个控制点调整；参数中确认／取消、锁定宽高／比例、向外扩展和构图线。当前帧尚缺动画数据。"
        "move" -> "松手移动整层或按软选区搬移像素；参数可选内容拾取图层／所属组、单位和方向键步进；Shift放大步进。"
        "pan" -> "拖动画布调整显示位置，不改变作品。"
        "measure" -> "拖动测量画布中的距离。"
        "sampler" -> "点击取色；双击设置前景／背景／只收集、合成／当前层、半径混合及指定调色板。"
        else -> "双击工具打开参数浮窗；浮窗关闭后仍保留当前工具和参数。"
    }

    fun describe(viewState: JSONObject = ArtStudioViewControl.describe()): JSONObject {
        val tools = JSONArray()
        implemented.forEach { (id, label, _) ->
            val item = JSONObject().put("id", id).put("label", label)
                .put("implemented", true).put("status", "basic")
                .put("parameterWindow", JSONObject().put("gesture", "double-click").put("available", true))
            if (id == "svg_text") item.put("advancedOptions", JSONArray(textAdvancedOptions.map { option ->
                JSONObject().put("label", option).put("implemented", true).put("status", "implemented")
            }))
            tools.put(item)
        }
        pending.forEach { item ->
            tools.put(JSONObject().put("id", item.id).put("label", item.label)
                .put("implemented", false).put("status", "planned")
                .put("source", item.source)
                .put("parameterWindow", JSONObject().put("gesture", "double-click").put("available", true)
                    .put("drawingEnabled", false)))
        }
        val zoomMode = viewState.getString("zoomToolMode")
        return JSONObject().put("tools", tools).put("textScope", ArtText.NOTICE).put("text", ArtTextSpec.info())
            .put("parameterWindowCapability", "$ART_ID.view.tool_options")
            .put("colorSampler",ArtColorSampler.info()).put("colorSelections",ArtColorSelection.info()).put("magneticSelection",ArtMagneticSelection.info())
            .put("comicPanels",ArtComicPanels.info())
            .put("bezierSelection",ArtBezierSelection.info())
            .put("encloseFill",ArtEncloseFill.info())
            .put("colorize",ArtColorize.defaults())
            .put("smartPatch",ArtSmartPatch.info())
            .put("referenceImages",ArtReferenceFiles.info())
            .put("assistants",JSONObject().put("types",JSONObject(ArtAssistants.types)).put("typeInfos",ArtAssistants.typeInfo())
                .put("units",JSONObject(ArtAssistantGeometry.units))
                .put("pending",JSONArray(ArtAssistants.pending)).put("maxObjects",ArtAssistants.MAX)
                .put("coordinateSpace","document").put("exported",false)
                .put("supportedBrushTools",JSONArray(ArtAssistants.brushTools.toList())))
            .put("calligraphy",JSONObject().put("capability","$ART_ID.shape.calligraphy")
                .put("coordinateSpace","layer-local").put("timeUnit","milliseconds")
                .put("maxSamples",ArtCalligraphy.MAX_SAMPLES)
                .put("editable","普通封闭轮廓，可用形状选择和贝塞尔节点工具修改")
                .put("pending",JSONArray(listOf("followSelectedPath","tabletAngle","massAndDrag","presets"))))
            .put("zoomTool", JSONObject().put("mode", zoomMode)
                .put("badge", if (zoomMode == "in") "大" else "小")
                .put("modeCapability", "$ART_ID.view.zoom_tool")
                .put("usage", "选中后再次点击图标切换放大/缩小；点击画布以该点为中心缩放。"))
    }
}
