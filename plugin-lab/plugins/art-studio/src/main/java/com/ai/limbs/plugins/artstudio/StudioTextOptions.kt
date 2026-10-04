package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** All text settings live in the shared double-click tool window; body input lives on canvas. */
@Composable
internal fun ColumnScope.StudioTextOptions(captured: JSONObject, fonts: JSONArray, busy: Boolean,
    liveInput: JSONObject?, onSubmit: (JSONObject) -> Unit) {
    var content by remember(captured) { mutableStateOf(TextFieldValue(captured.getString("content"))) }
    var spans by remember(captured) { mutableStateOf(JSONArray(captured.optJSONArray("spans")?.toString() ?: "[]")) }
    LaunchedEffect(liveInput?.optString("content"), liveInput?.optInt("selectionStart"), liveInput?.optInt("selectionEnd")) {
        liveInput?.let { live ->
            val next = live.getString("content")
            spans = ArtRichText.edit(content.text, next, spans)
            content = TextFieldValue(next, TextRange(live.optInt("selectionStart"), live.optInt("selectionEnd")))
        }
    }
    var mode by remember(captured) { mutableStateOf(captured.optString("sourceMode", "plain")) }
    var svg by remember(captured) { mutableStateOf(captured.optString("svgSource")) }
    var fontId by remember(captured) { mutableStateOf(captured.getString("fontId")) }
    var fontSize by remember(captured) { mutableStateOf(captured.getDouble("fontSize").toString()) }
    var boxWidth by remember(captured) { mutableStateOf(captured.getInt("boxWidth").toString()) }
    var lineSpacing by remember(captured) { mutableStateOf(captured.getDouble("lineSpacing").toString()) }
    var color by remember(captured) { mutableStateOf(captured.getString("color")) }
    var align by remember(captured) { mutableStateOf(captured.getString("align")) }
    var writing by remember(captured) { mutableStateOf(captured.optString("writingMode", "horizontal-tb")) }
    var direction by remember(captured) { mutableStateOf(captured.optString("direction", "auto")) }
    var orientation by remember(captured) { mutableStateOf(captured.optString("textOrientation", "mixed")) }
    var language by remember(captured) { mutableStateOf(captured.optString("language", "und")) }
    var features by remember(captured) { mutableStateOf(captured.optString("fontFeatures")) }
    var letterSpacing by remember(captured) { mutableStateOf(captured.optDouble("letterSpacing",0.0).toString()) }
    var wordSpacing by remember(captured) { mutableStateOf(captured.optDouble("wordSpacing",0.0).toString()) }
    var baselineShift by remember(captured) { mutableStateOf(captured.optDouble("baselineShift",0.0).toString()) }
    var strokeColor by remember(captured) { mutableStateOf(captured.optString("strokeColor","#00000000")) }
    var strokeWidth by remember(captured) { mutableStateOf(captured.optDouble("strokeWidth",0.0).toString()) }
    var underline by remember(captured) { mutableStateOf(captured.optBoolean("underline",false)) }
    var strike by remember(captured) { mutableStateOf(captured.optBoolean("strike",false)) }
    var geometryMode by remember(captured) { mutableStateOf(if(captured.has("textPath")) "path" else if(captured.has("shapeInside")) "inside" else "none") }
    var geometry by remember(captured) { mutableStateOf(JSONObject((captured.optJSONObject("textPath") ?: captured.optJSONObject("shapeInside"))?.toString() ?: "{}")) }
    var pathData by remember(captured) { mutableStateOf(geometry.optString("d")) }
    var pathStart by remember(captured) { mutableStateOf(geometry.optDouble("startOffset",0.0).toString()) }
    var normalOffset by remember(captured) { mutableStateOf(geometry.optDouble("normalOffset",0.0).toString()) }
    var padding by remember(captured) { mutableStateOf(geometry.optDouble("padding",0.0).toString()) }
    var fillRule by remember(captured) { mutableStateOf(geometry.optString("fillRule","nonzero")) }
    var x by remember(captured) { mutableStateOf(captured.getDouble("x").toString()) }
    var y by remember(captured) { mutableStateOf(captured.getDouble("y").toString()) }
    var fontMenu by remember { mutableStateOf(false) };var geometryMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val choices = (0 until fonts.length()).map { fonts.getJSONObject(it) }
    val selectedFont = choices.firstOrNull { it.getString("id") == fontId }
    val availableGeometry=captured.optJSONArray("geometryChoices") ?: JSONArray()
    fun parameters():JSONObject {
        val p=JSONObject(captured.toString());p.remove("geometryChoices")
        val body = if(mode!="svg") liveInput?.optString("content") ?: content.text else content.text
        val editedSpans = if(mode!="svg") ArtRichText.edit(content.text, body, spans) else spans
        p.put("content",body).put("fontId",fontId).put("fontSize",fontSize.toDouble()).put("boxWidth",boxWidth.toInt())
            .put("lineSpacing",lineSpacing.toDouble()).put("color",color).put("align",align).put("writingMode",writing)
            .put("direction",direction).put("textOrientation",orientation).put("language",language).put("fontFeatures",features)
            .put("letterSpacing",letterSpacing.toDouble()).put("wordSpacing",wordSpacing.toDouble()).put("baselineShift",baselineShift.toDouble())
            .put("strokeColor",strokeColor).put("strokeWidth",strokeWidth.toDouble()).put("underline",underline).put("strike",strike)
            .put("sourceMode",mode).put("clearGeometry",true).put("spans",editedSpans).put("x",x.toDouble()).put("y",y.toDouble())
        p.remove("textPath");p.remove("shapeInside")
        if(mode=="svg")p.put("svgSource",svg)
        else {p.remove("svgSource");p.remove("svgChunks");p.remove("svgViewBox")
            if(geometryMode!="none") {
                val g=JSONObject(geometry.toString())
                if(pathData.isNotBlank()){g.remove("shape");g.put("d",pathData)}
                g.put("startOffset",pathStart.toDouble()).put("normalOffset",normalOffset.toDouble()).put("padding",padding.toDouble()).put("fillRule",fillRule)
                p.put(if(geometryMode=="path") "textPath" else "shapeInside",g)
            }
        }
        return p
    }
    fun guarded(block:()->Unit) {try {error="";block()}catch(problem:Exception){error=problem.message.orEmpty()}}
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正文在画布上直接输入；此处设置字体、排版及高级文字参数。", style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=mode!="svg",onClick={guarded {
                        if(mode=="svg") {val parsed=ArtText.prepare(parameters());content=TextFieldValue(parsed.getString("content"));spans=parsed.getJSONArray("spans")}
                        mode="rich"
                    }},label={Text(if(mode=="svg") "转换为正文／富文本" else "正文／富文本")},enabled=!busy)
                    FilterChip(selected=mode=="svg",onClick={guarded {if(mode!="svg"){svg=ArtText.svgSource(parameters());mode="svg"}}},label={Text("SVG 源码")},enabled=!busy)
                }
                if(mode=="svg") {
                    OutlinedTextField(svg,{svg=it},label={Text("SVG 文字源码")},minLines=8,maxLines=14,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={guarded {val parsed=ArtText.prepare(parameters());error="源码解析通过：${parsed.getString("content").length} 个 UTF-16 单元；尚未绘制"}},enabled=!busy){Text("检查源码")}
                    Text("支持 text/tspan/textPath 和本地 defs；完整支持范围可从 text.info 查看。源码是当前编辑来源，正文参数不覆盖源码内的显式属性。",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text(if(content.text.isBlank()) "点击画布放置光标并输入文字。" else "正文：" + content.text.take(160), style=MaterialTheme.typography.bodySmall)
                    Text("长按画布中的文字选择范围，再在这里应用字符样式。竖排、路径及形状内排版在完成输入后由画室排版器生成；输入区编辑正文。", style=MaterialTheme.typography.bodySmall)
                }
                Box {
                    OutlinedButton(onClick={fontMenu=true},enabled=!busy){Text(selectedFont?.getString("label") ?: "原字体不可用，请选择字体")}
                    DropdownMenu(expanded=fontMenu,onDismissRequest={fontMenu=false},modifier=Modifier.heightIn(max=280.dp)) {
                        choices.forEach {item->DropdownMenuItem(text={Text(item.getString("label"))},onClick={fontId=item.getString("id");fontMenu=false})}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fontSize,{fontSize=it},label={Text("字号 px")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                    OutlinedTextField(boxWidth,{boxWidth=it},label={Text(if(writing=="horizontal-tb")"换行宽度 px" else "每列高度 px")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                }
                OutlinedTextField(color,{color=it},label={Text("文字颜色 #AARRGGBB")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=underline,onClick={underline=!underline},label={Text("下划线")},enabled=!busy)
                    FilterChip(selected=strike,onClick={strike=!strike},label={Text("删除线")},enabled=!busy)
                }
                if(mode!="svg") {
                    TextButton(onClick={guarded {
                        val value=parameters();val selected=content.selection
                        spans=ArtRichText.apply(content.text,spans,selected.min,selected.max,ArtTextSpec.style(value));mode="rich"
                    }},enabled=!busy&&!content.selection.collapsed){Text("将当前样式应用于选中文字")}
                    Text("已有 ${spans.length()} 个样式段；整体参数用于未单独设置样式的文字。字体菜单包含各字重／斜体，缺字不会自动替换字体。",style=MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("horizontal-tb" to "横排","vertical-rl" to "竖排右起","vertical-lr" to "竖排左起").forEach {(id,label)->FilterChip(selected=writing==id,onClick={writing=id},label={Text(label)},enabled=!busy&&mode!="svg")}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("auto" to "自动方向","ltr" to "从左到右","rtl" to "从右到左").forEach {(id,label)->FilterChip(selected=direction==id,onClick={direction=id},label={Text(label)},enabled=!busy&&mode!="svg")}
                }
                if(writing!="horizontal-tb") Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("mixed" to "混合字向","upright" to "全部直立","sideways" to "全部侧转").forEach {(id,label)->FilterChip(selected=orientation==id,onClick={orientation=id},label={Text(label)},enabled=!busy&&mode!="svg")}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("start" to "起端","center" to "居中","end" to "末端").forEach {(id,label)->FilterChip(selected=align==id,onClick={align=id},label={Text(label)},enabled=!busy&&mode!="svg")}
                }
                OutlinedTextField(lineSpacing,{lineSpacing=it},label={Text("行距倍数 1–3")},singleLine=true,enabled=!busy)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(letterSpacing,{letterSpacing=it},label={Text("字符簇间距 px")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                    OutlinedTextField(wordSpacing,{wordSpacing=it},label={Text("词间距 px")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                }
                OutlinedTextField(baselineShift,{baselineShift=it},label={Text("基线偏移 px（正为上移）")},singleLine=true,enabled=!busy)
                OutlinedTextField(strokeColor,{strokeColor=it},label={Text("描边颜色 #AARRGGBB")},singleLine=true,enabled=!busy)
                OutlinedTextField(strokeWidth,{strokeWidth=it},label={Text("描边宽度 px")},singleLine=true,enabled=!busy)
                OutlinedTextField(language,{language=it},label={Text("语言 BCP47，例如 zh-Hans／ar")},singleLine=true,enabled=!busy)
                OutlinedTextField(features,{features=it},label={Text("OpenType 参数，例如 kern=1,liga=1")},singleLine=true,enabled=!busy)
                if(mode!="svg") {
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf("none" to "文字框","path" to "沿路径","inside" to "形状内").forEach {(id,label)->FilterChip(selected=geometryMode==id,onClick={geometryMode=id},label={Text(label)},enabled=!busy)}
                    }
                    if(geometryMode!="none") {
                        OutlinedTextField(pathData,{pathData=it},label={Text("SVG 路径 d（也可选已有形状）")},minLines=2,maxLines=4,enabled=!busy,modifier=Modifier.fillMaxWidth())
                        Box {
                            OutlinedButton(onClick={geometryMenu=true},enabled=!busy&&availableGeometry.length()>0){Text("使用画布矢量形状快照（位置设为 0,0）")}
                            DropdownMenu(expanded=geometryMenu,onDismissRequest={geometryMenu=false},modifier=Modifier.heightIn(max=240.dp)) {
                                for(i in 0 until availableGeometry.length()) {val choice=availableGeometry.getJSONObject(i)
                                    DropdownMenuItem(text={Text(choice.getString("label"))},enabled=geometryMode!="inside"||choice.getBoolean("canFill"),onClick={geometry=JSONObject().put("shape",choice.getJSONObject("shape"));pathData="";x="0";y="0";geometryMenu=false})
                                }
                            }
                        }
                        if(geometryMode=="path") {
                            OutlinedTextField(pathStart,{pathStart=it},label={Text("沿路径起点偏移 px")},singleLine=true,enabled=!busy)
                            OutlinedTextField(normalOffset,{normalOffset=it},label={Text("路径法向偏移 px")},singleLine=true,enabled=!busy)
                        } else {
                            OutlinedTextField(padding,{padding=it},label={Text("形状内留白 px")},singleLine=true,enabled=!busy)
                            Row {listOf("nonzero","evenodd").forEach {id->FilterChip(selected=fillRule==id,onClick={fillRule=id},label={Text(id)},enabled=!busy)}}
                        }
                        Text("几何是本次保存的快照；原形状修改后不会自动改变文字。空间不够时明确拒绝保存。",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(x,{x=it},label={Text("文字锚点 X")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                    OutlinedTextField(y,{y=it},label={Text("文字锚点 Y")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                }
                Text(ArtText.NOTICE,style=MaterialTheme.typography.bodySmall)
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
                TextButton(enabled=!busy&&selectedFont!=null&&(mode!="svg"||svg.isNotBlank()), onClick={guarded {
                    val p=parameters()
                    val probe=ArtJsonCopy.objectValue(p)
                    if(mode!="svg"&&content.text.isBlank())probe.put("content","字").put("spans",JSONArray())
                    val normalized = ArtText.prepare(probe)
                    if(mode=="svg")p.put("content",normalized.getString("content"))
                    onSubmit(p)
                }}) {Text(if(busy)"处理中…" else "应用参数")}
    }
}
