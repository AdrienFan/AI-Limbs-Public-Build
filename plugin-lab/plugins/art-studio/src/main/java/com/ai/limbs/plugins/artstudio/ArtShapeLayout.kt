package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** Layout uses document axes, then maps each translation back to the selected layer. */
internal object ArtShapeLayout {
    val alignments=linkedMapOf("left" to "左对齐","center_x" to "水平居中","right" to "右对齐",
        "top" to "顶对齐","center_y" to "垂直居中","bottom" to "底对齐")
    val distributions=linkedMapOf("left" to "左边分布","center_x" to "水平中心分布","right" to "右边分布","gap_x" to "水平等间距",
        "top" to "顶边分布","center_y" to "垂直中心分布","bottom" to "底边分布","gap_y" to "垂直等间距")
    private data class Item(val id:String,val bounds:RectF)
    private fun coordinate(b:RectF,mode:String):Float=when(mode) {
        "left","gap_x"->b.left;"right"->b.right;"center_x"->b.centerX()
        "top","gap_y"->b.top;"bottom"->b.bottom;"center_y"->b.centerY()
        else->error("未知布局模式")
    }
    fun apply(state:JSONObject,layer:JSONObject,type:String,p:JSONObject):List<String> {
        val ids=ArtShapes.ids(p.getJSONArray("ids"));val all=ArtShapes.items(layer)
        require(ids.isNotEmpty()&&ids.distinct().size==ids.size&&ids.all {id->all.any {it.getString("id")==id}})
        val selected=ids.map {id->all.first {it.getString("id")==id}}
        require(selected.none {it.getBoolean("locked")||!it.getBoolean("visible")}) {"锁定或隐藏的形状不能布局"}
        if(type=="SHAPE_SHEAR") {
            val x=p.getDouble("shearX");val y=p.getDouble("shearY")
            require(x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=100&&kotlin.math.abs(y)<=100) {"剪切系数须为−100至100"}
            val b=requireNotNull(ArtShapes.bounds(layer,ids))
            val pivot=if(p.has("pivot"))p.getJSONArray("pivot") else JSONArray().put(b.centerX()).put(b.centerY())
            require(pivot.length()==2)
            val cx=pivot.getDouble(0);val cy=pivot.getDouble(1)
            require(cx.isFinite()&&cy.isFinite()&&kotlin.math.abs(cx)<=1000000&&kotlin.math.abs(cy)<=1000000)
            val delta=Matrix().apply {setSkew(x.toFloat(),y.toFloat(),cx.toFloat(),cy.toFloat())}
            ArtShapes.matrix(ArtShapes.encode(delta)) // Reject singular two-axis shears before modifying shapes.
            ArtShapes.transformIds(layer,ids,delta);return ids
        }
        val mode=p.getString("mode");val horizontal=mode in setOf("left","center_x","right","gap_x")
        val layerToDocument=ArtShapes.layerMatrix(state,layer);val inverse=Matrix();check(layerToDocument.invert(inverse))
        val items=selected.map {shape->
            Item(shape.getString("id"),ArtShapes.documentBounds(shape,layerToDocument))
        }
        fun translate(item:Item,amount:Float) {
            require(amount.isFinite())
            val vector=if(horizontal)floatArrayOf(amount,0f) else floatArrayOf(0f,amount)
            inverse.mapVectors(vector)
            ArtShapes.transformIds(layer,listOf(item.id),Matrix().apply {setTranslate(vector[0],vector[1])})
        }
        when(type) {
            "SHAPE_ALIGN"->{
                require(mode in alignments) {"未知对齐方式"}
                val reference=if(p.has("reference"))p.getString("reference") else "selection"
                require(reference in setOf("selection","canvas"))
                require(reference=="canvas"||ids.size>=2) {"所选范围对齐至少需要两个形状；单个对象可对齐画布"}
                val b=if(reference=="canvas")RectF(0f,0f,state.getInt("width").toFloat(),state.getInt("height").toFloat()) else RectF(items.first().bounds).apply {
                    items.drop(1).forEach {item->set(minOf(left,item.bounds.left),minOf(top,item.bounds.top),maxOf(right,item.bounds.right),maxOf(bottom,item.bounds.bottom))}
                }
                val target=coordinate(b,mode);items.forEach {translate(it,target-coordinate(it.bounds,mode))}
            }
            "SHAPE_DISTRIBUTE"->{
                require(mode in distributions&&ids.size>=3) {"分布至少需要三个形状及有效模式"}
                // Stable list sorting retains every shape, including equal coordinates.
                val ordered=items.sortedBy {coordinate(it.bounds,mode)}
                val first=coordinate(ordered.first().bounds,mode);val last=coordinate(ordered.last().bounds,mode)
                if(mode=="gap_x"||mode=="gap_y") {
                    fun extent(item:Item)=if(horizontal)item.bounds.width() else item.bounds.height()
                    val space=last+extent(ordered.last())-first-ordered.sumOf {extent(it).toDouble()}.toFloat()
                    val gap=space/(ordered.size-1);var position=first
                    ordered.forEachIndexed {index,item->
                        if(index>0&&index<ordered.lastIndex)translate(item,position-coordinate(item.bounds,mode))
                        position+=extent(item)+gap
                    }
                } else {
                    val step=(last-first)/(ordered.size-1)
                    ordered.forEachIndexed {index,item->if(index>0&&index<ordered.lastIndex)translate(item,first+step*index-coordinate(item.bounds,mode))}
                }
            }
            else->error("未知形状布局操作")
        }
        return ids
    }
    fun info()=JSONObject().put("align",JSONArray(alignments.keys)).put("distribute",JSONArray(distributions.keys))
        .put("axes","Align/distribute use document axes and geometric outline bounds, excluding stroke; layer/group transforms are respected. Align reference: selection (>=2) or canvas (>=1).")
        .put("distribution","At least 3 visible unlocked shapes in one vector layer. Stable coordinate order preserves ties. First/last stay fixed; gaps may be negative for overlapping shapes.")
        .put("shear","shape.shear: shearX/shearY are coefficients, x'=x+shearX*(y-pivotY), y'=y+shearY*(x-pivotX); pivot in layer-local pixels, defaults to selection center. Each coefficient -100..100; singular matrices rejected. UI shear mode uses edge midpoints, opposite edge fixed.")
        .put("styles","shape.style/style.objectStyle and shape.style_info: gradients, dashArray/dashOffset, strokeCap, strokeJoin, miterLimit, fillRule and separate fill/stroke opacity. Selection panel shares the implemented style editor.")
}
