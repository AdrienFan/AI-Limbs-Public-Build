package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** History projection reads committed events only; it never replays or renders a document. */
internal object ArtHistory {
    fun stacks(operations: JSONArray): Pair<List<String>, List<String>> {
        // Ordered sets retain selective undo/restore order without repeated linear removals.
        val undo = linkedSetOf<String>()
        val redo = linkedSetOf<String>()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val target = op.optJSONObject("parameters")?.optString("targetId") ?: ""
            when (op.getString("type")) {
                "ANIMATION_TIME" -> Unit
                "REVERT" -> { undo.remove(target); redo.add(target) }
                "RESTORE" -> { redo.remove(target); undo.add(target) }
                else -> { undo.add(op.getString("id")); redo.clear() }
            }
        }
        return undo.toList() to redo.toList()
    }
    private fun shapeName(kind: String): String = when(kind) {
        "line" -> "直线"; "rectangle" -> "矩形"; "ellipse" -> "椭圆"
        "polygon" -> "多边形"; "path" -> "路径"
        else -> error("未知历史矢量形状：$kind")
    }
    fun label(operation: JSONObject): String {
        return when (operation.getString("type")) {
                "ANIMATION_SETTINGS" -> "动画播放设置"
                "ANIMATION_KEY" -> when(operation.getJSONObject("parameters").getString("action")) {
                    "blank"->"新建动画空白帧";"duplicate"->"复制动画关键帧";"remove"->"移除动画关键帧"
                    "move"->"移动动画关键帧";"disable"->"停用动画轨道";else->error("未知关键帧操作")
                }
                "ANIMATION_TIME" -> "定位动画帧"
                "ANIMATION_POSES" -> "批量动画姿态"
                "MENU_LAYER_CHANGE" -> operation.getJSONObject("parameters").getString("label")
                "LAYER_CREATE", "IMAGE_IMPORT", "PASTE_IMAGE" -> "添加图层"
                "ASSISTANT_CREATE" -> "添加辅助尺规"
                "ASSISTANT_SELECT" -> "选择辅助尺规"
                "ASSISTANT_UPDATE" -> "编辑辅助尺规"
                "ASSISTANT_DELETE" -> "删除辅助尺规"
                "ASSISTANT_SETTINGS" -> "辅助尺规设置"
                "REFERENCE_SHOW" -> "参考图像整体显隐"
                "REFERENCE_ADD" -> "添加参考图像"
                "REFERENCE_BATCH_ADD" -> "导入参考集合"
                "REFERENCE_REPLACE" -> "刷新外部参考"
                "REFERENCE_EMBED" -> "参考图转为内嵌"
                "REFERENCE_SELECT" -> "选择参考图像"
                "REFERENCE_TRANSFORM" -> "变换参考图像"
                "REFERENCE_STYLE" -> "参考图像样式"
                "REFERENCE_DELETE" -> "删除参考图像"
                "VECTOR_LAYER_CREATE" -> "添加矢量图层"
                "SHAPE_COMIC_CUT" -> "漫画分格切分"
                "SHAPE_COMIC_MERGE" -> "漫画分格合并"
                "SHAPE_FREEHAND" -> "绘制或接续矢量徒手路径"
                "SHAPE_CREATE" -> when(operation.getJSONObject("parameters").optString("historyTool")) {
                    "vector_calligraphy" -> "矢量书法笔"
                    "vector_bezier" -> "可编辑贝塞尔路径"
                    else -> "添加矢量"+shapeName(operation.getJSONObject("parameters").getJSONObject("shape").getString("kind"))
                }
                "SHAPE_SELECT" -> "选择形状"
                "SHAPE_ALIGN" -> "对齐形状"
                "SHAPE_DISTRIBUTE" -> "分布形状"
                "SHAPE_SHEAR" -> "剪切形状"
                "SHAPE_TRANSFORM" -> "变换形状"
                "SHAPE_PATH_EDIT" -> "编辑路径节点"
                "SHAPE_PATH_TOPOLOGY" -> "断开或连接子路径"
                "SHAPE_PATH_CONVERT" -> "形状转路径"
                "SHAPE_PATH_COMBINE" -> "合成子路径对象"
                "SHAPE_DELETE" -> "删除形状"
                "SHAPE_STYLE" -> "形状样式"
                "TEXT_CREATE" -> "添加文字"
                "TEXT_UPDATE" -> "编辑文字"
                "GROUP_CREATE" -> "新建图层组"
                "LAYER_DELETE" -> "删除图层"
                "STROKE_ADD" -> when (operation.getJSONObject("parameters").optString("tool","pencil")) {
                    "gradient" -> ArtGradient.modes.getValue(operation.getJSONObject("parameters").optString("gradientMode","linear"))+"渐变"
                    "mirror" -> "多重画笔"
                    "dyna" -> "动态画笔"
                    "calligraphy" -> "斜头书法笔"
                    "line" -> "直线"
                    "rectangle" -> "矩形"
                    "ellipse" -> "椭圆"
                    "polygon" -> "多边形"
                    "polyline" -> "折线"
                    "bezier" -> "贝塞尔曲线"
                    "ink" -> "自由画笔"
                    "pencil" -> "铅笔"
                    "soft" -> "软笔"
                    "spray" -> "喷枪"
                    "eraser" -> "橡皮擦"
                    else -> "绘制笔画"
                }
                "STROKE_ERASE" -> "删除笔画"
                "SVG_APPLY" -> "应用SVG代码"
                "SVG_SELECT" -> "选择SVG对象"
                "TRANSFORM_AFFINE" -> "自由变换图层"
                "TRANSFORM_PIXELS" -> "变形图层或选区像素"
                "MOVE_LAYER" -> "移动图层"
                "MOVE_PIXELS" -> "移动选区像素"
                "TRANSFORM" -> "变换图层"
                "CROP" -> "裁剪画布"
                "LAYER_CROP" -> "裁剪图层边界"
                "CANVAS_RESIZE" -> "更改画布大小"
                "IMAGE_BACKGROUND" -> "更改图像背景色与透明度"
                "LAYER_RENAME" -> "重命名图层"
                "LAYER_SELECT" -> "选择图层"
                "LAYER_VISIBLE" -> "显示或隐藏图层"
                "LAYER_LOCK" -> "锁定或解锁图层"
                "LAYER_OPACITY" -> "调整图层不透明度"
                "LAYER_BLEND" -> "调整图层混合模式"
                "LAYER_PROPERTIES" -> "修改图层属性"
                "LAYER_MOVE", "LAYER_MOVE_STEP" -> "调整图层顺序"
                "COLORIZE_CREATE" -> "新建上色蒙版"
                "COLORIZE_STROKE" -> "颜色线索"
                "COLORIZE_REMOVE_STROKE","COLORIZE_CLEAR" -> "清理颜色线索"
                "COLORIZE_PALETTE" -> "颜色线索调色板"
                "COLORIZE_SETTINGS" -> "上色蒙版参数"
                "COLORIZE_OUTPUT" -> "更新填色结果"
                "COLORIZE_CONVERT" -> "蒙版转为绘画图层"
                "PIXEL_REPAIR" -> "智能修补"
                "PIXEL_EDIT" -> if (operation.getJSONObject("parameters").optString("mode") == "CLEAR")
                    "清除像素" else "填充像素"
                "PIXEL_PASTE" -> if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS") "填充相连区域" else if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS_ERASE") "擦除相连区域" else when(operation.getJSONObject("parameters").optString("action")) {
                    "ENCLOSE_FILL" -> "围合填充";"ENCLOSE_ERASE" -> "围合擦除";else -> "粘贴像素"
                }
                "LAYER_COPY" -> "复制图层"
                "SELECTION_TOOL" -> when(operation.getJSONObject("parameters").getString("tool")) {"contiguous"->"连续区域选区";"similar"->"相似色选区";"magnetic"->"磁性套索选区";"adjust"->"调整软选区";else->"创建基本选区"}
                "SELECTION_BEZIER" -> "贝塞尔曲线选区"
                "SELECTION_CREATE", "SELECTION_CLEAR", "SELECTION_EDIT" -> "修改选区"
                "DOCUMENT_RENAME" -> "重命名工程"
                else -> error("历史操作缺少足迹说明：${operation.getString("type")}")
            }
    }
    private fun category(type: String): String = when {
        type.startsWith("ANIMATION_") -> "动画"
        type.startsWith("STROKE_") || type.startsWith("PIXEL_") -> "栅格"
        type.startsWith("SHAPE_") -> "矢量"
        type.startsWith("SVG_") -> "SVG"
        type.startsWith("TEXT_") -> "文字"
        type.startsWith("SELECTION_") -> "选区"
        type.startsWith("REFERENCE_") -> "参考图"
        type.startsWith("ASSISTANT_") -> "辅助尺规"
        type.startsWith("COLORIZE_") -> "上色蒙版"
        type.startsWith("MOVE_") -> "移动"
        type.startsWith("TRANSFORM") -> "变形"
        type.startsWith("LAYER_") || type in setOf("VECTOR_LAYER_CREATE","GROUP_CREATE","MENU_LAYER_CHANGE","IMAGE_IMPORT","PASTE_IMAGE") -> "图层"
        type in setOf("CROP","CANVAS_RESIZE","IMAGE_BACKGROUND","DOCUMENT_RENAME") -> "画布"
        else -> error("历史操作缺少分类：$type")
    }
    private fun number(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    private fun summary(operation: JSONObject): String {
        val p = operation.getJSONObject("parameters")
        val facts = mutableListOf<String>()
        if(operation.has("animationFrame"))facts.add("动画帧："+operation.getInt("animationFrame"))
        if(operation.getString("type")=="ANIMATION_KEY")facts.add("关键帧："+p.getInt("frame"))
        if(operation.getString("type")=="ANIMATION_POSES")facts.add("姿态帧数："+p.getJSONArray("poses").length())
        if(p.has("name")) facts.add("名称："+p.getString("name"))
        if(operation.getString("type") in setOf("SHAPE_CREATE","SHAPE_FREEHAND")) {
            val shape=p.getJSONObject("shape")
            facts.add("几何点："+shape.getJSONArray("points").length())
        }
        if(p.has("points")) facts.add("路径点："+p.getJSONArray("points").length())
        if(p.has("ids")) facts.add("对象："+p.getJSONArray("ids").length()+"个")
        if(p.has("edits")) facts.add("节点编辑："+p.getJSONArray("edits").length()+"项")
        if(p.has("layers")) {
            val layers=p.getJSONArray("layers")
            facts.add("涉及图层："+layers.length())
            facts.add((0 until layers.length()).joinToString("、") {layers.getJSONObject(it).getString("name")})
            val shapes=(0 until layers.length()).sumOf {layers.getJSONObject(it).optJSONArray("shapes")?.length() ?: 0}
            if(shapes>0)facts.add("这些图层共含："+shapes+"个形状")
        }
        if(operation.getString("type")=="MENU_LAYER_CHANGE"&&p.has("insert"))facts.add("写入或替换图层："+p.getJSONArray("insert").length())
        if(operation.getString("type")=="MENU_LAYER_CHANGE"&&p.has("removeIds"))facts.add("移除图层："+p.getJSONArray("removeIds").length())
        for(key in listOf("width","height","opacity"))if(p.has(key)) {
            val name=when(key) {"width"->"宽度";"height"->"高度";else->"不透明度"}
            facts.add(name+"："+number(p.getDouble(key))+if(key=="opacity")"" else "px")
        }
        if(p.has("brushPresetId"))facts.add("使用笔刷预设")
        if(p.has("profileId"))facts.add("使用书法配置档")
        return facts.joinToString(" · ")
    }
    fun describe(doc: JSONObject): JSONObject {
        val operations=doc.getJSONArray("operations")
        val (undoStack,redoStack)=stacks(operations)
        val operationById=(0 until operations.length()).map {operations.getJSONObject(it)}
            .filter {it.getString("type") !in setOf("REVERT","RESTORE","ANIMATION_TIME")}.associateBy {it.getString("id")}
        fun operationLabel(id:String?)=if(id==null)"" else label(operationById.getValue(id))
        // A state represents the first N currently reachable edits. Redone edits stay
        // visible as future states until a new edit starts a different branch.
        val reachableIds = undoStack + redoStack.asReversed()
        val originalOrder = operationById.keys.withIndex().associate { it.value to it.index }
        var newestOriginalIndex = -1
        val timeline = JSONArray().put(JSONObject().put("id", "")
            .put("label", "初始画布").put("category","画布").put("summary","工程初始状态").put("actor", doc.getString("createdBy"))
            .put("timestamp", 0L))
        for (operationId in reachableIds) {
            val operation = operationById.getValue(operationId)
            val originalIndex = originalOrder.getValue(operationId)
            val reappliedOutOfOrder = originalIndex < newestOriginalIndex
            newestOriginalIndex = maxOf(newestOriginalIndex, originalIndex)
            val entry = JSONObject().put("id", operationId)
                .put("label", if (reappliedOutOfOrder)
                    "重新应用 · " + operationLabel(operationId) else operationLabel(operationId))
                .put("actor", operation.getString("actor"))
                 .put("type", operation.getString("type")).put("category",category(operation.getString("type")))
                .put("summary",summary(operation)).put("timestamp", operation.optLong("timestamp", 0L))
            if (operation.getString("type") == "STROKE_ADD") {
                val stroke = operation.getJSONObject("parameters")
                entry.put("tool", stroke.optString("tool", "pencil"))
                    .put("color", stroke.getString("color"))
            }
            val parameters=operation.getJSONObject("parameters")
            if(parameters.has("historyTool"))entry.put("tool",parameters.getString("historyTool"))
            if(operation.getString("type")=="SHAPE_FREEHAND")entry.put("tool","vector_freehand")
            timeline.put(entry)
        }

        val reachableSet=reachableIds.toSet()
        val otherBranches=JSONArray()
        for((id,operation) in operationById)if(id !in reachableSet)otherBranches.put(JSONObject()
            .put("id",id).put("label",label(operation)).put("category",category(operation.getString("type")))
            .put("summary",summary(operation)).put("actor",operation.getString("actor"))
            .put("type",operation.getString("type")).put("timestamp",operation.optLong("timestamp",0L)))
        return JSONObject().put("otherBranches",otherBranches)
            .put("documentId",doc.getString("id")).put("revision",operations.length())
            .put("timeline",timeline).put("position",undoStack.size)
            .put("canUndo",undoStack.isNotEmpty()).put("canRedo",redoStack.isNotEmpty())
            .put("undoLabel",operationLabel(undoStack.lastOrNull())).put("redoLabel",operationLabel(redoStack.lastOrNull()))
            .put("historyStats",JSONObject().put("eventCount",operations.length()).put("editCount",operationById.size)
                .put("reachableSteps",reachableIds.size).put("otherBranchSteps",operationById.size-reachableIds.size))
    }
}
