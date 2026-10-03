package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** Plugin-owned SVG scene profile. Native raster leaves are stable references; edits return to native layers. */
internal object ArtSceneSvg {
    const val MAX_BYTES=1048576
    private val graphic=setOf("path","rect","ellipse","circle","line","polygon","polyline")
    private val paintKeys=setOf("fill","stroke","fill-opacity","stroke-opacity","fill-rule","stroke-width","stroke-linecap","stroke-linejoin","stroke-miterlimit","stroke-dasharray","stroke-dashoffset")
    data class Document(val source:String,val index:JSONArray,val objects:JSONArray,val selected:List<String>)
    data class Plan(val layers:JSONArray,val background:String,val textLayers:List<String>,val touched:List<String>)
    fun layerId(id:String)="layer_$id"
    fun shapeId(id:String)="shape_$id"
    fun info()=JSONObject().put("profile","AI_LIMBS_SCENE_SVG_1").put("completeSvgStandard",false)
        .put("scopes",JSONArray(listOf("document","objects","append")))
        .put("editable","vector primitives, M/L/H/V/C/S/Q/T/Z paths, object-local linear/radial gradients, dash/cap/join, layer/group affine transforms/opacity/visibility/blend, canvas background; text source JSON in metadata[type=application/vnd.ai-limbs.text+json]")
        .put("nativePixels","image href=ail-layer:<uuid>, native fingerprint and dimensions are read-only; source brushes/assets retained, no Base64 in code; this profile needs the studio resource resolver, not a standalone portable SVG")
        .put("unsupported","scripts, external resources, DTD/entities, use, filters, animation, CSS stylesheets, arbitrary clip masks, SVG arc A, document resize or layer reparent/delete through source; use existing tools for these")
        .put("limits","1MiB UTF-8, 8192 XML nodes, depth32, native vector limits; API offsets/index use UTF-16; append creates one native vector layer; text source changes use the existing text engine")
        .put("nativeSemantics","Open paths are stroke-only; rect rx/ry must match; SVG2 #RRGGBBAA colors; only referenced gradients persist; editable text is native JSON metadata, not arbitrary SVG text markup")
        .put("flow","svg.read -> edit -> svg.validate/preview -> svg.apply; all writes bind documentId/expectedRevision; svg.select/hit connects code and canvas without requiring phone SVG panel")
    fun esc(s:String)=s.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;")
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun matrix(a:JSONArray)="matrix("+(0 until a.length()).joinToString(" "){a.getDouble(it).toString()}+")"
    private fun css(s:String)=ArtSceneSvgGeometry.css(s)
    fun selected(state:JSONObject):List<String> {
        val layer=ArtMenuOperations.active(state) ?: return emptyList()
        return if(layer.getString("kind")=="vector"&&ArtShapes.selected(state,layer.getString("id")).isNotEmpty())ArtShapes.selected(state,layer.getString("id")).map(::shapeId) else listOf(layerId(layer.getString("id")))
    }
    private fun shapeTag(s:JSONObject,defs:StringBuilder):String {
        val style=ArtObjectStyle.settings(s);val id=shapeId(s.getString("id"))
        val attributes=StringBuilder(" id=\"$id\" transform=\"${matrix(s.getJSONArray("matrix"))}\" opacity=\"${s.getDouble("opacity")}\" display=\"${if(s.getBoolean("visible"))"inline" else "none"}\" data-locked=\"${s.getBoolean("locked")}\"")
        for(key in listOf("fill","stroke")) {
            val g=style.optJSONObject(key+"Gradient")
            val value=if(g!=null) {
                val name="grad_${s.getString("id")}_$key";val a=g.getJSONArray("start");val b=g.getJSONArray("end");val linear=g.getString("type")=="linear"
                defs.append("    <${if(linear)"linearGradient" else "radialGradient"} id=\"$name\" gradientUnits=\"userSpaceOnUse\" ")
                if(linear)defs.append("x1=\"${a.getDouble(0)}\" y1=\"${a.getDouble(1)}\" x2=\"${b.getDouble(0)}\" y2=\"${b.getDouble(1)}\">")
                else defs.append("cx=\"${a.getDouble(0)}\" cy=\"${a.getDouble(1)}\" r=\"${kotlin.math.hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1))}\">")
                val stops=g.getJSONArray("stops");for(i in 0 until stops.length()){val stop=stops.getJSONArray(i);defs.append("<stop offset=\"${stop.getDouble(0)}\" stop-color=\"${css(stop.getString(1))}\"/>")}
                defs.append("</${if(linear)"linearGradient" else "radialGradient"}>\n");"url(#$name)"
            } else css(s.getString(key))
            attributes.append(" $key=\"$value\" $key-opacity=\"${style.getDouble(key+"Opacity")}\"")
        }
        attributes.append(" stroke-width=\"${s.getDouble("strokeWidth")}\" fill-rule=\"${style.getString("fillRule")}\" stroke-linecap=\"${style.getString("strokeCap")}\" stroke-linejoin=\"${style.getString("strokeJoin")}\" stroke-miterlimit=\"${style.getDouble("miterLimit")}\" stroke-dashoffset=\"${style.getDouble("dashOffset")}\"")
        val dash=style.getJSONArray("dashArray");attributes.append(" stroke-dasharray=\"${if(dash.length()==0)"none" else (0 until dash.length()).joinToString(" "){dash.getDouble(it).toString()}}\"")
        val points=s.getJSONArray("points");val a=points.getJSONArray(0);val b=points.getJSONArray(1)
        val geometry=when(s.getString("kind")) {
            "line"->"line x1=\"${a.getDouble(0)}\" y1=\"${a.getDouble(1)}\" x2=\"${b.getDouble(0)}\" y2=\"${b.getDouble(1)}\""
            "rectangle"->"rect x=\"${minOf(a.getDouble(0),b.getDouble(0))}\" y=\"${minOf(a.getDouble(1),b.getDouble(1))}\" width=\"${kotlin.math.abs(b.getDouble(0)-a.getDouble(0))}\" height=\"${kotlin.math.abs(b.getDouble(1)-a.getDouble(1))}\" rx=\"${s.optDouble("cornerRadius",0.0)}\""
            "ellipse"->"ellipse cx=\"${(a.getDouble(0)+b.getDouble(0))/2}\" cy=\"${(a.getDouble(1)+b.getDouble(1))/2}\" rx=\"${kotlin.math.abs(b.getDouble(0)-a.getDouble(0))/2}\" ry=\"${kotlin.math.abs(b.getDouble(1)-a.getDouble(1))/2}\""
            "polygon"->"polygon points=\""+(0 until points.length()).joinToString(" "){val p=points.getJSONArray(it);"${p.getDouble(0)},${p.getDouble(1)}"}+"\""
            "path"->"path d=\"${ArtSceneSvgGeometry.pathCode(s)}\""
            else->error("未知矢量形状")
        };return "<$geometry$attributes/>"
    }
    fun export(snapshot:JSONObject,scope:String="document",objectIds:List<String> = emptyList()):Document {
        require(scope in setOf("document","objects"));val state=snapshot.getJSONObject("state");val layers=ArtMenuOperations.layers(state)
        val known=layers.map {layerId(it.getString("id"))}+layers.filter {it.getString("kind")=="vector"}.flatMap {ArtShapes.items(it).map {s->shapeId(s.getString("id"))}}
        require(objectIds.distinct().size==objectIds.size&&objectIds.all {it in known}) {"SVG 对象不存在"}
        if(scope=="objects")require(objectIds.isNotEmpty()) {"局部读取须指定objectIds"}
        val include=mutableSetOf<String>();val fullLayers=mutableSetOf<String>();val selectedShapes=mutableSetOf<String>()
        if(scope=="document"){include.addAll(layers.map {it.getString("id")});fullLayers.addAll(include)}else for(id in objectIds) {
            if(id.startsWith("layer_")){val raw=id.removePrefix("layer_");val tree=ArtMenuOperations.subtree(state,raw);include.addAll(tree.map {it.getString("id")});fullLayers.addAll(tree.map {it.getString("id")})}
            else {selectedShapes.add(id);include.add(layers.first {it.getString("kind")=="vector"&&ArtShapes.items(it).any {s->shapeId(s.getString("id"))==id}}.getString("id"))}
        }
        repeat(layers.size){for(l in layers)if(l.getString("id") in include&&l.optString("parentId").isNotBlank())include.add(l.getString("parentId"))}
        val defs=StringBuilder();val body=StringBuilder();val objects=JSONArray()
        fun children(parent:String,depth:Int) {
            require(depth<=32)
            for(l in layers.filter {it.optString("parentId")==parent&&it.getString("id") in include}) {
                val id=l.getString("id");val kind=l.getString("kind");val pad="  ".repeat(depth+1)
                body.append("$pad<g id=\"${layerId(id)}\" data-kind=\"$kind\" data-locked=\"${l.getBoolean("locked")}\" data-name=\"${esc(l.getString("name"))}\" data-blend=\"${l.getString("blend")}\" transform=\"${matrix(ArtShapes.encode(ArtShapes.localMatrix(l)))}\" opacity=\"${l.getDouble("opacity")}\" display=\"${if(l.getBoolean("visible"))"inline" else "none"}\"")
                l.optJSONArray("cropClip")?.let {clip->val name="clip_$id";defs.append("    <clipPath id=\"$name\"><path d=\"")
                    for(i in 0 until clip.length()){val p=clip.getJSONArray(i);defs.append("${if(i==0)"M" else "L"} ${p.getDouble(0)} ${p.getDouble(1)} ")};if(clip.length()>0)defs.append("Z")
                    defs.append("\"/></clipPath>\n");body.append(" clip-path=\"url(#$name)\"")}
                body.append(">\n");objects.put(JSONObject().put("id",layerId(id)).put("layerId",id).put("kind",kind).put("name",l.getString("name")))
                if(kind=="group")children(id,depth+1)
                else if(kind=="vector") { for(s in ArtShapes.items(l))if(scope=="document"||id in fullLayers||shapeId(s.getString("id")) in selectedShapes) {
                    body.append("$pad  ${shapeTag(s,defs)}\n");objects.put(JSONObject().put("id",shapeId(s.getString("id"))).put("layerId",id).put("shapeId",s.getString("id")).put("kind",s.getString("kind")))
                } }
                if(kind !in setOf("group","vector")&&(scope=="document"||id in fullLayers)) {
                    body.append("$pad  <image id=\"pixels_$id\" href=\"ail-layer:$id\" data-native-hash=\"${hash(l.toString())}\" x=\"0\" y=\"0\" width=\"${state.getInt("width")}\" height=\"${state.getInt("height")}\"/>\n")
                    if(kind=="text")body.append("$pad  <metadata id=\"text_$id\" type=\"application/vnd.ai-limbs.text+json\">${esc(l.getJSONObject("text").toString())}</metadata>\n")
                }
                body.append("$pad</g>\n")
            }
        };children("",0)
        val source=buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${state.getInt("width")}\" height=\"${state.getInt("height")}\" viewBox=\"0 0 ${state.getInt("width")} ${state.getInt("height")}\" data-profile=\"AI_LIMBS_SCENE_SVG_1\">\n")
            append("  <defs>\n$defs  </defs>\n")
            if(scope=="document")append("  <rect id=\"canvas_background\" x=\"0\" y=\"0\" width=\"${state.getInt("width")}\" height=\"${state.getInt("height")}\" fill=\"${css(state.getString("background"))}\"/>\n")
            append(body);append("</svg>\n")
        };require(source.toByteArray().size<=MAX_BYTES){"SVG 代码超过1MiB，请读取较少对象"}
        return Document(source,ArtSvgCodeIndex.json(source),objects,selected(state))
    }
    fun parse(source:String):Element {
        require(source.toByteArray().size in 1..MAX_BYTES){"SVG 源码最大1MiB"}
        require(!Regex("<!DOCTYPE|<!ENTITY",RegexOption.IGNORE_CASE).containsMatchIn(source)){"SVG 不接受 DTD 或实体声明"}
        val indexed=ArtSvgCodeIndex.ranges(source)
        val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;isExpandEntityReferences=false}
        val builder=factory.newDocumentBuilder().apply {setEntityResolver {_,_->error("SVG 不接受外部实体")};setErrorHandler(object:org.xml.sax.ErrorHandler {override fun warning(e:SAXParseException){throw e};override fun error(e:SAXParseException){throw e};override fun fatalError(e:SAXParseException){throw e}})}
        val document=builder.parse(InputSource(StringReader(source)))
        for(i in 0 until document.childNodes.length)require(document.childNodes.item(i).nodeType!=Node.PROCESSING_INSTRUCTION_NODE){"不允许 XML 处理指令"}
        val root=document.documentElement;require(root.localName=="svg") {"根元素必须为svg"}
        var nodes=0;val ids=mutableSetOf<String>()
        fun inspect(e:Element,depth:Int) {
            require(++nodes<=8192&&depth<=32);require(e.namespaceURI=="http://www.w3.org/2000/svg"){"SVG 必须使用标准命名空间"}
            require(e.localName in graphic+setOf("svg","g","defs","linearGradient","radialGradient","stop","clipPath","image","metadata")){"尚未支持SVG元素：${e.localName}"}
            if(e.hasAttribute("id")){val id=e.getAttribute("id");require(id.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}"))&&ids.add(id)){"SVG id 无效或重复"}}
            val allowed=setOf("id")+(if(e.localName in graphic+setOf("g","svg"))paintKeys+setOf("transform","opacity","display","data-locked") else emptySet())+when(e.localName){
                "svg"->setOf("width","height","viewBox","data-profile")
                "g"->setOf("data-kind","data-name","data-blend","clip-path")
                "path"->setOf("d")
                "rect"->setOf("x","y","width","height","rx","ry")
                "ellipse"->setOf("cx","cy","rx","ry")
                "circle"->setOf("cx","cy","r")
                "line"->setOf("x1","y1","x2","y2")
                "polygon","polyline"->setOf("points")
                "linearGradient"->setOf("x1","y1","x2","y2","gradientUnits")
                "radialGradient"->setOf("cx","cy","r","gradientUnits")
                "stop"->setOf("offset","stop-color","stop-opacity")
                "image"->setOf("href","x","y","width","height","data-native-hash")
                "metadata"->setOf("type")
                else->emptySet()
            }
            for(i in 0 until e.attributes.length){val a=e.attributes.item(i);if(a.namespaceURI=="http://www.w3.org/2000/xmlns/")continue;require(a.namespaceURI==null&&a.nodeName in allowed){"尚未支持SVG属性：${a.nodeName}"}}
            for(i in 0 until e.childNodes.length){val n=e.childNodes.item(i)
                if(n is Element) {
                    val accepted=when(e.localName) {
                        "svg","g"->graphic+setOf("g","defs","metadata","image")
                        "defs"->setOf("linearGradient","radialGradient","clipPath")
                        "linearGradient","radialGradient"->setOf("stop")
                        "clipPath"->setOf("path")
                        else->emptySet()
                    }
                    require(n.localName in accepted){"SVG元素位置不支持：${e.localName}/${n.localName}"};inspect(n,depth+1)
                } else {
                    require(n.nodeType in setOf(Node.TEXT_NODE,Node.CDATA_SECTION_NODE,Node.COMMENT_NODE)){"不允许 XML 处理指令"}
                    require(e.localName=="metadata"||n.nodeType==Node.COMMENT_NODE||n.nodeValue.isNullOrBlank()){ "非文字节点不接受正文" }
                }
            }
            if(e.hasAttribute("display"))require(e.getAttribute("display") in setOf("inline","none")){"display只支持inline/none"}
        };inspect(root,0);require(indexed.map {it.id}.toSet()==ids){"对象ID须直接书写，不使用XML实体编码"};return root
    }
    private fun sameRoot(e:Element,old:Element) {
        val names=((0 until e.attributes.length).map {e.attributes.item(it).nodeName}+(0 until old.attributes.length).map {old.attributes.item(it).nodeName}).toSet()
        require(names.all {e.getAttribute(it)==old.getAttribute(it)}) {"现有作品须保留svg根属性"}
    }
    private fun elements(root:Element):List<Element> {val out=mutableListOf(root);for(i in 0 until root.childNodes.length)(root.childNodes.item(i) as? Element)?.let {out.addAll(elements(it))};return out}
    private fun children(e:Element)=(0 until e.childNodes.length).mapNotNull {e.childNodes.item(it) as? Element}
    private fun signature(e:Element):String {
        val attrs=(0 until e.attributes.length).map {e.attributes.item(it)}.sortedBy {it.nodeName}.joinToString {it.nodeName+"="+it.nodeValue}
        return e.localName+"["+attrs+"]"+children(e).joinToString {signature(it)}+if(children(e).isEmpty())e.textContent.trim() else ""
    }
    private fun gradient(e:Element):JSONObject {
        require(e.localName in setOf("linearGradient","radialGradient")&&e.getAttribute("gradientUnits")=="userSpaceOnUse"){"渐变仅支持对象局部 userSpaceOnUse 线性/径向渐变"}
        fun n(k:String,d:Double=0.0)=ArtSceneSvgGeometry.number(e,k,d)
        val linear=e.localName=="linearGradient";val x=n(if(linear)"x1" else "cx");val y=n(if(linear)"y1" else "cy")
        val stops=JSONArray();for(stop in children(e)){require(stop.localName=="stop");val a=ArtSceneSvgGeometry.number(stop,"stop-opacity",1.0);require(a in 0.0..1.0)
            val color=ArtSceneSvgGeometry.color(stop.getAttribute("stop-color"));val alpha=(color.substring(1,3).toInt(16)*a).toInt();stops.put(JSONArray(listOf(ArtSceneSvgGeometry.number(stop,"offset"),"#%02x".format(alpha)+color.substring(3))))}
        return JSONObject().put("type",if(linear)"linear" else "radial").put("start",JSONArray(listOf(x,y))).put("end",JSONArray(if(linear)listOf(n("x2"),n("y2"))else listOf(x+n("r").also {require(it>0){"径向渐变半径须大于零"}},y))).put("stops",stops)
    }
    fun plan(snapshot:JSONObject,source:String,scope:String,objectIds:List<String> = emptyList(),newLayerName:String="SVG 绘画"):Plan {
        require(scope in setOf("document","objects","append"));require(scope=="objects"||objectIds.isEmpty()){ "objectIds只用于局部范围" }
        val root=parse(source);val state=snapshot.getJSONObject("state")
        require(!root.hasAttribute("data-profile")||root.getAttribute("data-profile")=="AI_LIMBS_SCENE_SVG_1"){"未知SVG场景配置"}
        for(k in listOf("width","height"))if(root.hasAttribute(k))require(ArtSceneSvgGeometry.number(root,k)==state.getDouble(k)){"SVG 不直接更改画布尺寸，请使用画布尺寸工具"}
        if(root.hasAttribute("viewBox"))require(ArtSceneSvgGeometry.numbers(root.getAttribute("viewBox"))==listOf(0.0,0.0,state.getDouble("width"),state.getDouble("height"))){"viewBox 须使用当前画布1:1像素"}
        val ids=elements(root).filter {it.hasAttribute("id")}.associateBy {it.getAttribute("id")}
        fun paint(value:String):JSONObject {val ref=Regex("url\\(#([A-Za-z_][A-Za-z0-9_.-]*)\\)").matchEntire(value)?.groupValues?.get(1) ?: error("渐变须使用本地#引用");return gradient(ids[ref] ?: error("渐变定义不存在"))}
        val layers=JSONArray(state.getJSONArray("layers").toString());val all=(0 until layers.length()).map {layers.getJSONObject(it)};val text=mutableListOf<String>();val touched=mutableSetOf<String>()
        if(scope=="append") {
            val layer=JSONObject().put("id",UUID.randomUUID().toString()).put("name",newLayerName.trim()).put("kind","vector").put("parentId","")
                .put("visible",true).put("locked",false).put("opacity",1).put("blend","normal").put("x",0).put("y",0).put("scale",1).put("rotation",0).put("strokes",JSONArray()).put("shapes",JSONArray())
            require(layer.getString("name").length in 1..64)
            fun visit(e:Element,parent:Matrix,inherited:Map<String,String>,opacity:Double,visible:Boolean) {
                if(e.localName=="defs")return
                require(e.localName!="metadata"){"append不导入文字元数据；请使用文字工具"}
                require(!e.hasAttribute("clip-path")&&!e.hasAttribute("data-kind")&&!e.hasAttribute("data-blend")&&!e.hasAttribute("data-name")){"append只支持图形、分组与绘图属性"}
                require(!e.hasAttribute("data-locked")||e.getAttribute("data-locked")=="false"){"append不从代码设置对象锁定"}
                val properties=inherited+paintKeys.filter {e.hasAttribute(it)}.associateWith {e.getAttribute(it)}
                val matrix=Matrix(parent).apply {preConcat(ArtSceneSvgGeometry.transform(e.getAttribute("transform")))}
                val ownOpacity=opacity*ArtSceneSvgGeometry.number(e,"opacity",1.0);require(ownOpacity in 0.0..1.0);val show=visible&&e.getAttribute("display")!="none"
                if(e.localName in graphic) {
                    for((k,v) in properties)if(!e.hasAttribute(k))e.setAttribute(k,v)
                    val shape=ArtSceneSvgGeometry.shape(e,null,::paint).put("matrix",ArtShapes.encode(matrix)).put("opacity",ownOpacity).put("visible",show)
                    require(shape.getString("kind")!="path"||ArtPathTopology.parts(shape).all {it.closed}||shape.getString("fill").substring(1,3)=="00"){"原生开放路径仅支持描线，请显式fill=none"}
                    layer.getJSONArray("shapes").put(ArtShapes.normalize(shape))
                } else {require(e.localName in setOf("svg","g")){"append 支持图形和分组；文字请使用文字入口"};children(e).forEach {visit(it,matrix,properties,ownOpacity,show)}}
            };visit(root,Matrix(),emptyMap(),1.0,true);require(layer.getJSONArray("shapes").length()>0){"SVG没有图形"};layers.put(layer);ArtShapes.validateDocument(JSONObject(state.toString()).put("layers",layers));return Plan(layers,state.getString("background"),emptyList(),listOf(layer.getString("id")))
        }
        require(!root.hasAttribute("id")){"作品根节点不设置对象ID"}
        val baseline=export(snapshot,scope,objectIds);val original=parse(baseline.source);val oldElements=elements(original).filter {it.hasAttribute("id")}.associateBy {it.getAttribute("id")}
        sameRoot(root,original)
        require(children(root).all {it.localName=="defs"||(it.localName=="g"&&it.getAttribute("id").startsWith("layer_"))||(scope=="document"&&it.getAttribute("id")=="canvas_background")}) {"完整/局部代码中的图形须放在对应矢量图层；新增作品使用append"}
        for(clip in elements(original).filter {it.localName=="clipPath"})require(ids[clip.getAttribute("id")]?.let {signature(it)}==signature(clip)){"裁剪定义为只读，请使用裁剪工具"}
        val originalsByParent=oldElements.values.filter {it.getAttribute("id").startsWith("layer_")}.groupBy {(it.parentNode as? Element)?.getAttribute("id")}
        for((parent,group) in originalsByParent){val node=if(parent.isNullOrBlank())root else (ids[parent] ?: error("缺少父图层：$parent"));require(children(node).filter {it.localName=="g"}.map {it.getAttribute("id")}==group.map {it.getAttribute("id")}) {"图层顺序请使用图层面板修改"}}
        val expectedLayers=oldElements.keys.filter {it.startsWith("layer_")}.toSet();val actualLayers=ids.keys.filter {it.startsWith("layer_")}.toSet()
        require(actualLayers==expectedLayers){"代码编辑须保留图层ID和父子结构；新绘画使用scope=append"}
        val editableLayers=if(scope=="document")all.map {it.getString("id")}.toSet() else objectIds.filter {it.startsWith("layer_")}.flatMap {ArtMenuOperations.subtree(state,it.removePrefix("layer_")).map {l->l.getString("id")}}.toSet()
        val editableShapes=if(scope=="document")oldElements.keys.filter {it.startsWith("shape_")}.toSet() else objectIds.filter {it.startsWith("shape_")}.toSet()
        fun sameAttributes(a:Element,b:Element,allowed:Set<String>) {
            val names=((0 until a.attributes.length).map {a.attributes.item(it).nodeName}+(0 until b.attributes.length).map {b.attributes.item(it).nodeName}).toSet()
            require(names.all {it in allowed || a.getAttribute(it)==b.getAttribute(it)}) {"该节点包含不可修改的身份、来源或裁剪属性"}
        }
        var background=state.getString("background")
        if(scope=="document") {
            val e=ids["canvas_background"] ?: error("须保留canvas_background");require(e.localName=="rect");sameAttributes(e,oldElements.getValue("canvas_background"),setOf("fill"));background=ArtSceneSvgGeometry.color(e.getAttribute("fill"))
        }
        for(id in expectedLayers) {
            val e=ids.getValue(id);val old=oldElements.getValue(id);require(e.localName=="g");require((e.parentNode as? Element)?.getAttribute("id")== (old.parentNode as? Element)?.getAttribute("id")){"代码不支持重新归属图层"}
            val raw=id.removePrefix("layer_");val layer=all.first {it.getString("id")==raw};val mutable=raw in editableLayers
            sameAttributes(e,old,if(mutable)setOf("transform","opacity","display","data-name","data-blend")else emptySet())
            if(mutable) {
                val matrix=ArtSceneSvgGeometry.transform(e.getAttribute("transform"));val beforeMatrix=ArtSceneSvgGeometry.transform(old.getAttribute("transform"))
                if(ArtShapes.encode(matrix).toString()!=ArtShapes.encode(beforeMatrix).toString())layer.put("x",0).put("y",0).put("rotation",0).put("scale",1).put("affine",ArtShapes.encode(matrix))
                layer.put("opacity",ArtSceneSvgGeometry.number(e,"opacity",1.0)).put("visible",e.getAttribute("display")!="none").put("name",e.getAttribute("data-name")).put("blend",e.getAttribute("data-blend"))
                require(layer.getDouble("opacity") in 0.0..1.0&&layer.getString("name").length in 1..64&&layer.getString("blend") in setOf("normal","multiply","screen","add"))
            }
            val kind=layer.getString("kind")
            if(kind=="group")require(children(e).all {it.localName=="g"&&it.getAttribute("id") in expectedLayers}){"组图层仅接受原子图层"}
            if(kind=="vector") {
                val before=ArtShapes.items(layer);val newShapes=JSONArray();val seen=mutableSetOf<String>()
                for(child in children(e)) {
                    require(child.localName in graphic){"矢量图层只接受图形节点"};val sid=child.getAttribute("id");val sourceShape=before.firstOrNull {shapeId(it.getString("id"))==sid}
                    if(sourceShape==null){require(mutable&&!sid.startsWith("shape_")){"新对象请省略id或使用临时ID，不覆盖其他对象"}}
                    else {require(sid in oldElements && seen.add(sid)){"对象不在当前代码范围内或ID重复"};sameAttributes(child,oldElements.getValue(sid),paintKeys+setOf("transform","opacity","display","d","x","y","width","height","rx","ry","cx","cy","r","x1","y1","x2","y2","points"))}
                    if(!mutable&&sid !in editableShapes){require(sourceShape!=null);sameAttributes(child,oldElements.getValue(sid),emptySet());newShapes.put(sourceShape)}
                    else {
                        val prior=oldElements[sid]
                        val unchanged=sourceShape!=null&&prior!=null&&signature(child)==signature(prior)&&listOf("fill","stroke").all {key->
                            val value=child.getAttribute(key);if(value.startsWith("url(#")){val ref=value.removePrefix("url(#").removeSuffix(")");ids[ref]?.let {signature(it)}==oldElements[ref]?.let {signature(it)}}else true
                        }
                        newShapes.put(if(unchanged)sourceShape else ArtSceneSvgGeometry.shape(child,sourceShape,::paint))
                    }
                }
                if(!mutable){require(seen==oldElements.keys.filter {it.startsWith("shape_")&&(oldElements.getValue(it).parentNode as? Element)?.getAttribute("id")==id}.toSet());val replacements=(0 until newShapes.length()).associate {val s=newShapes.getJSONObject(it);s.getString("id") to s};layer.put("shapes",JSONArray(before.map {replacements[it.getString("id")] ?: it}))}
                else layer.put("shapes",newShapes)
            } else if(kind!="group") {
                val image=children(e).singleOrNull {it.localName=="image"} ?: error("须保留原像素来源节点")
                require(image.getAttribute("id")=="pixels_$raw");sameAttributes(image,oldElements.getValue("pixels_$raw"),emptySet());require(children(e).all {it.localName in setOf("image","metadata")})
                if(kind=="text") {
                    val metadata=children(e).singleOrNull {it.localName=="metadata"} ?: error("须保留文字源节点")
                    sameAttributes(metadata,oldElements.getValue("text_$raw"),emptySet());val input=JSONObject(metadata.textContent)
                    if(metadata.textContent!=oldElements.getValue("text_$raw").textContent){require(mutable);layer.put("text",ArtText.prepare(input));text.add(raw)}
                } else require(children(e).none {it.localName=="metadata"})
            }
        }
        for(e in elements(root).filter {it.localName=="g"})require(e.getAttribute("id") in expectedLayers){"现有作品中的分组须使用原图层ID"}
        for(layer in all) {
            val old=ArtMenuOperations.layers(state).first {it.getString("id")==layer.getString("id")}
            if(layer.toString()!=old.toString()) {
                require(!ArtMenuOperations.isLocked(state,old)){"锁定图层不能通过SVG修改"}
                if(old.getString("kind")=="vector")for(s in ArtShapes.items(old).filter {it.getBoolean("locked")})require(ArtShapes.items(layer).firstOrNull {it.getString("id")==s.getString("id")}?.toString()==s.toString()){ "锁定对象不能通过SVG修改" }
                touched.add(layer.getString("id"))
            }
        }
        val candidate=JSONObject(state.toString()).put("layers",layers);ArtShapes.validateDocument(candidate)
        return Plan(layers,background,text,touched.toList())
    }
}
