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
                val commands = shape.getJSONArray("commands")
                require(commands.length() in 1..ArtFreehand.MAX_SEGMENTS)
                var consumed = 1
                for (n in 0 until commands.length()) consumed += when (commands.getString(n)) {
                    "L" -> 1
                    "C" -> 3
                    else -> error("路径仅支持直线L和三次贝塞尔C")
                }
                require(consumed == points.length()) { "路径命令与几何点数量不匹配" }
                shape.put("closed", shape.optBoolean("closed", false))
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
                val b=bounds(shape);lm.mapRect(b)
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
            when(shape.getString("kind")) {
                "line" -> { moveTo(x,y);lineTo(u,v) }
                "rectangle" -> addRect(minOf(x,u),minOf(y,v),maxOf(x,u),maxOf(y,v),Path.Direction.CW)
                "ellipse" -> addOval(minOf(x,u),minOf(y,v),maxOf(x,u),maxOf(y,v),Path.Direction.CW)
                "polygon" -> {
                    moveTo(x,y)
                    for(n in 1 until a.length()) { val p=a.getJSONArray(n);lineTo(p.getDouble(0).toFloat(),p.getDouble(1).toFloat()) }
                    close()
                }
                "path" -> {
                    moveTo(x,y)
                    val commands=shape.getJSONArray("commands")
                    var index=1
                    fun next():FloatArray {
                        val p=a.getJSONArray(index++)
                        return floatArrayOf(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())
                    }
                    for(n in 0 until commands.length()) when(commands.getString(n)) {
                        "L" -> { val p=next();lineTo(p[0],p[1]) }
                        "C" -> { val c1=next();val c2=next();val p=next()
                            cubicTo(c1[0],c1[1],c2[0],c2[1],p[0],p[1]) }
                        else -> error("不支持的路径命令")
                    }
                    if(shape.getBoolean("closed")) close()
                }
                else -> error("不支持的形状")
            }
        }
    }
    fun canFill(shape:JSONObject):Boolean = shape.getString("kind") != "line" &&
        (shape.getString("kind") != "path" || shape.getBoolean("closed"))

    fun draw(canvas: Canvas, layer: JSONObject) {
        for(shape in items(layer)) {
            if(!shape.getBoolean("visible") || shape.getDouble("opacity")==0.0) continue
            val path=path(shape)
            canvas.save();canvas.concat(matrix(shape.getJSONArray("matrix")))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth=shape.getDouble("strokeWidth").toFloat()
                strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
            }
            fun setColor(key:String) {
                val c=Color.parseColor(shape.getString(key))
                paint.color=c;paint.alpha=(Color.alpha(c)*shape.getDouble("opacity")).toInt()
            }
            if(canFill(shape)) {
                setColor("fill");paint.style=Paint.Style.FILL;canvas.drawPath(path,paint)
            }
            setColor("stroke");paint.style=Paint.Style.STROKE;canvas.drawPath(path,paint)
            canvas.restore()
        }
    }
    private fun coverage(shape:JSONObject):Path {
        val source=path(shape);val area=Path()
        if(canFill(shape) && Color.alpha(Color.parseColor(shape.getString("fill")))>0)
            area.addPath(source)
        if(Color.alpha(Color.parseColor(shape.getString("stroke")))>0) {
            val outline=Path()
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style=Paint.Style.STROKE;strokeWidth=shape.getDouble("strokeWidth").toFloat()
                strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
            }.getFillPath(source,outline)
            check(area.op(outline,Path.Op.UNION)) { "形状描边合并失败" }
        }
        area.transform(matrix(shape.getJSONArray("matrix")))
        return area
    }
    fun bounds(shape:JSONObject):RectF = RectF().also {
        val p=path(shape);p.transform(matrix(shape.getJSONArray("matrix")));p.computeBounds(it,true)
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
            "SHAPE_CREATE" -> {
                require(all.size<512) { "单个矢量层最多512个对象" }
                val shape=normalize(p.getJSONObject("shape"))
                require(all.none { it.getString("id")==shape.getString("id") })
                val count=all.sumOf { it.getJSONArray("points").length() }+shape.getJSONArray("points").length()
                require(count<=32768) { "矢量层顶点数超过32768" }
                layer.getJSONArray("shapes").put(shape);choose(listOf(shape.getString("id")))
            }
            "SHAPE_SELECT" -> choose(ids(p.getJSONArray("ids")))
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
                require(style.keys().asSequence().all { it in setOf("fill","stroke","strokeWidth","opacity") }) { "尚未实现此形状属性" }
                for(shape in all.filter { it.getString("id") in ids }) {
                    require(!shape.getBoolean("locked")) { "形状已锁定" }
                    val next=JSONObject(shape.toString());style.keys().forEach { key -> next.put(key,style.get(key)) }
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
            val b=bounds(shape);lm.mapRect(b)
            shapes.put(JSONObject(shape.toString()).put("documentBounds",JSONObject()
                .put("x",b.left.toDouble()).put("y",b.top.toDouble())
                .put("width",b.width().toDouble()).put("height",b.height().toDouble())))
        }
        return JSONObject().put("layerId",layerId).put("shapes",shapes)
            .put("selectedIds",JSONArray(selected(state,layerId)))
            .put("visible",visible(state,layer)).put("locked",ArtMenuOperations.isLocked(state,layer))
    }
}
