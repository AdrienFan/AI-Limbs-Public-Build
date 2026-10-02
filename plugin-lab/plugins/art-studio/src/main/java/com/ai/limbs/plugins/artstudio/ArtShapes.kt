package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Geometry remains editable source; no pixels are used as object identity. */
internal object ArtShapes {
    val kinds = setOf("line", "rectangle", "ellipse", "polygon", "path")
    private val identity = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    fun ids(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
    fun items(layer: JSONObject): List<JSONObject> {
        require(layer.getString("kind") == "vector") { "形状选择需要矢量图层" }
        val a = layer.getJSONArray("shapes")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun layer(state: JSONObject, id: String): JSONObject =
        ArtMenuOperations.layers(state).firstOrNull { it.getString("id") == id }
            ?: error("矢量图层不存在")
    fun visible(state: JSONObject, layer: JSONObject): Boolean {
        val all = ArtMenuOperations.layers(state)
        var current = layer
        repeat(all.size + 1) {
            if (!current.getBoolean("visible") || current.getDouble("opacity") == 0.0) return false
            val parent = current.optString("parentId")
            if (parent.isBlank()) return true
            current = all.firstOrNull { it.getString("id") == parent } ?: error("父图层不存在")
        }
        error("图层组循环引用")
    }
    fun selected(state: JSONObject, layerId: String): List<String> {
        val selection = state.optJSONObject("shapeSelection") ?: return emptyList()
        if (selection.getString("layerId") != layerId) return emptyList()
        val existing = items(layer(state, layerId)).map { it.getString("id") }.toSet()
        return ids(selection.getJSONArray("ids")).filter { it in existing }
    }
    fun matrix(a: JSONArray): Matrix {
        require(a.length() == 6) { "对象矩阵需要六个数字 a,b,c,d,tx,ty" }
        val v = (0 until 6).map { a.getDouble(it) }
        require(v.all { it.isFinite() && kotlin.math.abs(it) <= 1000000.0 })
        require(kotlin.math.abs(v[0] * v[3] - v[1] * v[2]) >= 0.00000001) { "对象矩阵不能退化" }
        return Matrix().apply { setValues(floatArrayOf(v[0].toFloat(),v[2].toFloat(),v[4].toFloat(),
            v[1].toFloat(),v[3].toFloat(),v[5].toFloat(),0f,0f,1f)) }
    }
    fun encode(m: Matrix): JSONArray {
        val v = FloatArray(9); m.getValues(v)
        return JSONArray(listOf(v[0].toDouble(),v[3].toDouble(),v[1].toDouble(),
            v[4].toDouble(),v[2].toDouble(),v[5].toDouble()))
    }
    fun normalize(source: JSONObject): JSONObject {
        val shape = JSONObject(source.toString())
        require(shape.getString("id").matches(Regex("[a-f0-9-]{36}"))) { "形状编号无效" }
        val kind = shape.getString("kind"); require(kind in kinds) { "尚未实现此矢量形状" }
        val points = shape.getJSONArray("points")
        when (kind) {
            "path" -> {
                require(points.length() in 2..ArtFreehand.MAX_GEOMETRY_POINTS)
                shape.put("closed",shape.optBoolean("closed",false))
                ArtPathTopology.parts(shape)
            }
            "polygon" -> require(points.length() in 3..2048)
            else -> require(points.length() == 2)
        }
        for (n in 0 until points.length()) {
            val p = points.getJSONArray(n)
            require(p.length() == 2 && (0..1).all {
                p.getDouble(it).isFinite() && kotlin.math.abs(p.getDouble(it)) <= 1000000.0
            }) { "形状坐标无效" }
        }
        for (key in listOf("fill","stroke")) {
            val color = shape.optString(key, if (key == "fill") "#00000000" else "#FF161616")
            require(color.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色必须是 #AARRGGBB" }
            shape.put(key,color)
        }
        val width = shape.optDouble("strokeWidth",2.0)
        val opacity = shape.optDouble("opacity",1.0)
        require(width.isFinite() && width in 0.1..512.0 && opacity in 0.0..1.0)
        shape.put("strokeWidth",width).put("opacity",opacity)
            .put("visible",shape.optBoolean("visible",true)).put("locked",shape.optBoolean("locked",false))
        if (!shape.has("matrix")) shape.put("matrix",JSONArray(identity))
        matrix(shape.getJSONArray("matrix"))
        if(kind=="rectangle" || shape.has("cornerRadius")) {
            val radius=shape.optDouble("cornerRadius",0.0)
            require(radius.isFinite()&&radius in 0.0..16384.0 && (kind=="rectangle" || radius==0.0)) {"仅矩形支持圆角半径"}
            if(kind=="rectangle")shape.put("cornerRadius",radius)
        }
        if(shape.has("objectStyle"))shape.put("objectStyle",ArtObjectStyle.settings(shape))
        val rawBounds = RectF(); path(shape).computeBounds(rawBounds,true)
        require(rawBounds.width() > 0f || rawBounds.height() > 0f) { "请画出非零大小的形状" }
        if (kind in setOf("rectangle", "ellipse", "polygon")) require(rawBounds.width() > 0f && rawBounds.height() > 0f)
        val transformed=bounds(shape)
        require(listOf(transformed.left,transformed.top,transformed.right,transformed.bottom)
            .all { it.isFinite() && kotlin.math.abs(it)<=1000000f }) { "形状变换超出可编辑坐标范围" }
        val painted=RectF();coverage(shape).computeBounds(painted,true)
        require(listOf(painted.left,painted.top,painted.right,painted.bottom)
            .all { it.isFinite() && kotlin.math.abs(it)<=1000000f }) { "形状描边范围过大" }
        return shape
    }
    fun validateDocument(state:JSONObject) {
        for(layer in ArtMenuOperations.layers(state).filter { it.getString("kind")=="vector" }) {
            val all=items(layer)
            require(all.size<=512 && all.sumOf { it.getJSONArray("points").length() }<=32768)
            require(all.map { it.getString("id") }.distinct().size==all.size)
            require(layer.getJSONArray("strokes").length()==0)
            val lm=layerMatrix(state,layer)
            matrix(encode(lm))
            for(shape in all) {
                normalize(shape)
                val b=documentBounds(shape,lm)
                require(listOf(b.left,b.top,b.right,b.bottom).all {
                    it.isFinite()&&kotlin.math.abs(it)<=1000000f
                }) { "矢量形状或父组变换超出可编辑范围" }
            }
        }
    }
    fun path(shape: JSONObject): Path {
        val a = shape.getJSONArray("points")
        val p0=a.getJSONArray(0);val p1=a.getJSONArray(1)
        val x=p0.getDouble(0).toFloat();val y=p0.getDouble(1).toFloat()
        val u=p1.getDouble(0).toFloat();val v=p1.getDouble(1).toFloat()
        return Path().apply {
            fillType=if(ArtObjectStyle.settings(shape).getString("fillRule")=="evenodd")Path.FillType.EVEN_ODD else Path.FillType.WINDING
            when(shape.getString("kind")) {
                "line" -> { moveTo(x,y);lineTo(u,v) }
                "rectangle" -> {
                    val radius=minOf(shape.optDouble("cornerRadius",0.0).toFloat(),abs(u-x)/2,abs(v-y)/2)
                    addRoundRect(minOf(x,u),minOf(y,v),maxOf(x,u),maxOf(y,v),radius,radius,Path.Direction.CW)
                }
                "ellipse" -> addOval(minOf(x,u),minOf(y,v),maxOf(x,u),maxOf(y,v),Path.Direction.CW)
                "polygon" -> {
                    moveTo(x,y)
                    for(n in 1 until a.length()) { val p=a.getJSONArray(n);lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat()) }
                    close()
                }
                "path" -> addPath(ArtPathTopology.path(shape))
                else -> error("不支持的形状")
            }
        }
    }
    fun canFill(shape:JSONObject):Boolean = shape.getString("kind") != "line" &&
        (shape.getString("kind") != "path" || ArtPathTopology.parts(shape).any {it.closed})

    fun draw(canvas: Canvas, layer: JSONObject) {
        for(shape in items(layer)) {
            if(!shape.getBoolean("visible") || shape.getDouble("opacity")==0.0) continue
            val path=path(shape)
            canvas.save();canvas.concat(matrix(shape.getJSONArray("matrix")))
            if(shape.has("objectStyle")) {
                val save=canvas.saveLayer(null,Paint().apply {alpha=(255*shape.getDouble("opacity")).toInt()})
                try {
                    if(canFill(shape))canvas.drawPath(fillPath(shape),ArtObjectStyle.paint(shape,false))
                    canvas.drawPath(path,ArtObjectStyle.paint(shape,true))
                } finally {canvas.restoreToCount(save)}
            } else {
                fun paint(stroke:Boolean)=ArtObjectStyle.paint(shape,stroke).apply {alpha=(alpha*shape.getDouble("opacity")).toInt()}
                if(canFill(shape))canvas.drawPath(fillPath(shape),paint(false));canvas.drawPath(path,paint(true))
            }
            canvas.restore()
        }
    }
    private fun fillPath(shape:JSONObject)=if(shape.getString("kind")=="path")ArtPathTopology.path(shape,true) else path(shape)
    private fun coverage(shape:JSONObject):Path {
        val source=path(shape);val area=Path()
        if(canFill(shape) && ArtObjectStyle.visible(shape,false))
            area.addPath(fillPath(shape))
        if(ArtObjectStyle.visible(shape,true)) {
            val outline=Path();ArtObjectStyle.paint(shape,true,false).getFillPath(source,outline)
            check(area.op(outline,Path.Op.UNION)) { "形状描边合并失败" }
        }
        area.transform(matrix(shape.getJSONArray("matrix")))
        return area
    }
    fun bounds(shape:JSONObject):RectF = RectF().also {
        val p=path(shape);p.transform(matrix(shape.getJSONArray("matrix")));p.computeBounds(it,true)
    }
    fun documentBounds(shape:JSONObject,layerToDocument:Matrix):RectF = RectF().also {
        val outline=path(shape);outline.transform(matrix(shape.getJSONArray("matrix")))
        outline.transform(layerToDocument);outline.computeBounds(it,true)
        require(listOf(it.left,it.top,it.right,it.bottom).all {value->value.isFinite()})
    }
    fun bounds(layer:JSONObject,ids:List<String>):RectF? {
        val selected=items(layer).filter { it.getString("id") in ids }
        if(selected.isEmpty()) return null
        val result=bounds(selected.first())
        selected.drop(1).forEach { val b=bounds(it);result.set(minOf(result.left,b.left),minOf(result.top,b.top),
            maxOf(result.right,b.right),maxOf(result.bottom,b.bottom)) }
        return result
    }
    private fun contains(area:Path,x:Float,y:Float):Boolean {
        // Region is integer based: upsample to preserve thin outlines and subpixel hits.
        val scaled=Path(area);scaled.transform(Matrix().apply { setScale(16f,16f) })
        val b=RectF();scaled.computeBounds(b,true)
        val px=x*16f;val py=y*16f
        if(b.isEmpty || px<b.left || px>b.right || py<b.top || py>b.bottom) return false
        val ix=px.toInt();val iy=py.toInt()
        val region=Region()
        // Scan only the queried pixel neighbourhood, never a whole large vector canvas.
        region.setPath(scaled,Region(ix-2,iy-2,ix+3,iy+3))
        return region.contains(ix,iy)
    }
    fun hit(layer:JSONObject,x:Float,y:Float,tolerance:Float):String? {
        require(x.isFinite()&&y.isFinite()&&tolerance.isFinite()&&tolerance in 0f..1000000f)
        return items(layer).asReversed().firstOrNull { shape ->
            if(!shape.getBoolean("visible")||shape.getDouble("opacity")==0.0) false else {
                val area=coverage(shape)
                if(contains(area,x,y)) true else if(tolerance>0f) {
                    val halo=Path()
                    Paint().apply { style=Paint.Style.STROKE;strokeWidth=tolerance*2f }.getFillPath(area,halo)
                    contains(halo,x,y)
                } else false
            }
        }?.getString("id")
    }
    fun box(layer:JSONObject,clip:Path,contained:Boolean):List<String> =
        items(layer).filter { shape ->
            if(!shape.getBoolean("visible")||shape.getDouble("opacity")==0.0) false else {
                val area=coverage(shape)
                if(area.isEmpty) false else {
                    val result=Path()
                    check(result.op(area,clip,if(contained) Path.Op.DIFFERENCE else Path.Op.INTERSECT)) {
                        "形状框选计算失败"
                    }
                    if(contained) result.isEmpty else !result.isEmpty
                }
            }
        }.map { it.getString("id") }
    fun layerMatrix(state:JSONObject,layer:JSONObject):Matrix {
        val all=ArtMenuOperations.layers(state);val chain=mutableListOf(layer)
        var parent=layer.optString("parentId")
        repeat(all.size) {
            if(parent.isNotBlank()) {
                val p=all.firstOrNull { it.getString("id")==parent } ?: error("父图层不存在")
                chain.add(p);parent=p.optString("parentId")
            }
        }
        require(parent.isBlank()) { "图层组循环引用" }
        return Matrix().apply {
            for(p in chain) {
                postScale(p.getDouble("scale").toFloat(),p.getDouble("scale").toFloat())
                postRotate(p.getDouble("rotation").toFloat())
                postTranslate(p.getDouble("x").toFloat(),p.getDouble("y").toFloat())
            }
        }
    }
    fun transformIds(layer:JSONObject,ids:List<String>,delta:Matrix) {
        require(ids.isNotEmpty()&&ids.distinct().size==ids.size)
        val all=items(layer);require(ids.all { id -> all.any { it.getString("id")==id } })
        for(shape in all.filter { it.getString("id") in ids }) {
            require(!shape.getBoolean("locked")) { "形状已锁定" }
            val next=matrix(shape.getJSONArray("matrix")).apply { postConcat(delta) }
            val encoded=encode(next);matrix(encoded)
            shape.put("matrix",encoded)
        }
    }
    fun edit(state:JSONObject,type:String,p:JSONObject) {
        val layer=layer(state,p.getString("layerId"));val all=items(layer)
        require(visible(state,layer)) { "矢量图层或父组不可见" }
        if(type!="SHAPE_SELECT") require(!ArtMenuOperations.isLocked(state,layer)) { "矢量图层或父组已锁定" }
        fun choose(ids:List<String>) {
            require(ids.distinct().size==ids.size && ids.all { id -> items(layer).any { it.getString("id")==id } })
            state.put("selectedLayerId",layer.getString("id"))
                .put("shapeSelection",JSONObject().put("layerId",layer.getString("id")).put("ids",JSONArray(ids)))
        }
        when(type) {
            "SHAPE_FREEHAND" -> choose(listOf(ArtFreehandConnect.apply(layer,p)))
            "SHAPE_CREATE" -> {
                require(all.size<512) { "单个矢量层最多512个对象" }
                val shape=normalize(p.getJSONObject("shape"))
                require(all.none { it.getString("id")==shape.getString("id") })
                val count=all.sumOf { it.getJSONArray("points").length() }+shape.getJSONArray("points").length()
                require(count<=32768) { "矢量层顶点数超过32768" }
                layer.getJSONArray("shapes").put(shape);choose(listOf(shape.getString("id")))
            }
            "SHAPE_SELECT" -> choose(ids(p.getJSONArray("ids")))
            "SHAPE_PATH_EDIT" -> {
                val id=p.getString("id");val shape=all.firstOrNull { it.getString("id")==id }
                    ?: error("路径对象不存在")
                require(!shape.getBoolean("locked")) { "路径对象已锁定" }
                val result=ArtPathGeometry.edited(shape,p.getJSONArray("edits"))
                result.keys().forEach { key -> shape.put(key,result.get(key)) }
                choose(listOf(id))
            }
            "SHAPE_PATH_TOPOLOGY", "SHAPE_PATH_CONVERT", "SHAPE_PATH_COMBINE" -> {
                val ids=if(type=="SHAPE_PATH_TOPOLOGY")listOf(p.getString("id")) else ids(p.getJSONArray("ids"))
                require(ids.isNotEmpty()&&ids.distinct().size==ids.size&&ids.all {id->all.any {it.getString("id")==id}})
                val chosen=ids.map {id->all.first {it.getString("id")==id}};require(chosen.none {it.getBoolean("locked")||!it.getBoolean("visible")})
                when(type) {
                    "SHAPE_PATH_TOPOLOGY"->{val shape=chosen.single();val next=ArtPathTopology.topology(shape,p);next.keys().forEach {shape.put(it,next.get(it))};choose(ids)}
                    "SHAPE_PATH_CONVERT"->{chosen.forEach {shape->val next=ArtPathTopology.converted(shape);shape.remove("cornerRadius");next.keys().forEach {shape.put(it,next.get(it))}};choose(ids)}
                    else->{
                        require(chosen.size>=2&&chosen.all {it.getString("kind")=="path"}) {"先将形状转路径，再合成子路径对象"}
                        val target=chosen.first();val inverse=Matrix();require(matrix(target.getJSONArray("matrix")).invert(inverse))
                        val parts=mutableListOf<ArtPathTopology.Part>()
                        chosen.forEach {shape->
                            val transform=matrix(shape.getJSONArray("matrix")).apply {postConcat(inverse)}
                            fun map(v:ArtPathGeometry.Vec):ArtPathGeometry.Vec {val a=floatArrayOf(v.x.toFloat(),v.y.toFloat());transform.mapPoints(a);return ArtPathGeometry.Vec(a[0].toDouble(),a[1].toDouble())}
                            ArtPathTopology.parts(shape).forEach {part->parts.add(ArtPathTopology.Part(part.nodes.map {ArtPathGeometry.Node(map(it.point),it.incoming?.let {h->map(h)},it.outgoing?.let {h->map(h)},it.type)}.toMutableList(),part.closed))}
                        }
                        val next=ArtPathTopology.write(target,parts);next.keys().forEach {target.put(it,next.get(it))}
                        layer.put("shapes",JSONArray(all.filter {it.getString("id")==ids.first()||it.getString("id") !in ids}));choose(listOf(ids.first()))
                    }
                }
            }
            "SHAPE_ALIGN", "SHAPE_DISTRIBUTE", "SHAPE_SHEAR" -> choose(ArtShapeLayout.apply(state,layer,type,p))
            "SHAPE_TRANSFORM" -> {
                val ids=ids(p.getJSONArray("ids"))
                transformIds(layer,ids,matrix(p.getJSONArray("matrix")));choose(ids)
            }
            "SHAPE_DELETE" -> {
                val ids=ids(p.getJSONArray("ids"));choose(ids);require(ids.isNotEmpty())
                require(all.filter { it.getString("id") in ids }.none { it.getBoolean("locked") }) { "形状已锁定" }
                layer.put("shapes",JSONArray(all.filterNot { it.getString("id") in ids }));choose(emptyList())
            }
            "SHAPE_STYLE" -> {
                val ids=ids(p.getJSONArray("ids"));choose(ids);require(ids.isNotEmpty())
                val style=p.getJSONObject("style")
                require(style.keys().asSequence().all { it in setOf("fill","stroke","strokeWidth","opacity","cornerRadius","objectStyle") }) { "尚未实现此形状属性" }
                if(style.has("cornerRadius"))require(all.filter {it.getString("id") in ids}.all {it.getString("kind")=="rectangle"}) {"圆角半径只适用于矩形"}
                for(shape in all.filter { it.getString("id") in ids }) {
                    require(!shape.getBoolean("locked")) { "形状已锁定" }
                    val next=JSONObject(shape.toString());style.keys().forEach { key ->
                        if(key=="objectStyle") {val merged=ArtObjectStyle.settings(next);val patch=style.getJSONObject(key);patch.keys().forEach {merged.put(it,patch.get(it))};next.put(key,merged)}
                        else next.put(key,style.get(key))
                    }
                    val normalized=normalize(next);normalized.keys().forEach { key -> shape.put(key,normalized.get(key)) }
                }
            }
            else -> error("形状操作未实现")
        }
    }
    fun describe(state:JSONObject,layerId:String):JSONObject {
        val layer=layer(state,layerId)
        val shapes=JSONArray()
        val lm=layerMatrix(state,layer)
        for(shape in items(layer)) {
            val b=documentBounds(shape,lm)
            shapes.put(JSONObject(shape.toString()).put("documentBounds",JSONObject()
                .put("x",b.left.toDouble()).put("y",b.top.toDouble())
                .put("width",b.width().toDouble()).put("height",b.height().toDouble())))
        }
        return JSONObject().put("layerId",layerId).put("shapes",shapes)
            .put("selectedIds",JSONArray(selected(state,layerId)))
            .put("visible",visible(state,layer)).put("locked",ArtMenuOperations.isLocked(state,layer))
    }
}
