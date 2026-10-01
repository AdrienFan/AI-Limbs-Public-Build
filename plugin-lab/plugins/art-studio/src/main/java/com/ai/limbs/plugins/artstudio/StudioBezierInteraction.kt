package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

internal class StudioBezierInteraction(private val view:View) {
    private var context:JSONObject?=null
    private var draft=mutableListOf<ArtPathGeometry.Node>()
    private var pressed=false
    private var target=""
    private var frame=Matrix()
    private var lastTap=0L
    private var editContext:JSONObject?=null
    private var editNodes=mutableListOf<ArtPathGeometry.Node>()
    private var editClosed=false
    private var editIndex=-1
    private var editSide=""
    private var grab=ArtPathGeometry.Vec(0.0,0.0)
    private var pointerStart=ArtPathGeometry.Vec(0.0,0.0)
    private val density get()=view.resources.displayMetrics.density
    val hasDraft get()=context!=null

    fun cancel() {
        val had=context!=null||editContext!=null
        context=null;draft.clear();pressed=false;editContext=null;editNodes.clear()
        if(had) view.invalidate()
    }
    fun interrupt() {
        if(pressed&&target.isBlank()&&draft.isNotEmpty()) draft.removeAt(draft.lastIndex)
        pressed=false;editContext=null;editNodes.clear()
        if(draft.isEmpty()) context=null
        view.invalidate()
    }
    private fun checkContext(p:JSONObject,document:String,revision:Int,layer:String) {
        require(p.getString("documentId")==document&&p.getInt("expectedRevision")==revision&&
            p.getString("layerId")==layer) { "工程已更新，请重新操作路径" }
    }
    private fun screen(p:ArtPathGeometry.Vec,m:Matrix):FloatArray =
        floatArrayOf(p.x.toFloat(),p.y.toFloat()).also { m.mapPoints(it) }
    private fun local(e:MotionEvent,m:Matrix):ArtPathGeometry.Vec {
        val inverse=Matrix();check(m.invert(inverse))
        val p=floatArrayOf(e.x,e.y);inverse.mapPoints(p)
        require(p.all { it.isFinite()&&kotlin.math.abs(it)<=1000000f }) { "坐标超出可编辑范围" }
        return ArtPathGeometry.Vec(p[0].toDouble(),p[1].toDouble())
    }
    private fun near(e:MotionEvent,p:ArtPathGeometry.Vec,m:Matrix,r:Float=12f):Boolean {
        val s=screen(p,m);return hypot(e.x-s[0],e.y-s[1])<=r*density
    }
    private fun sameFrame(m:Matrix) {
        val a=FloatArray(9);val b=FloatArray(9);frame.getValues(a);m.getValues(b)
        require(a.indices.all { kotlin.math.abs(a[it]-b[it])<=0.0001f }) { "视图已改变，请重新操作路径" }
    }
    private fun selected(state:JSONObject,layerId:String):JSONObject? {
        val layer=ArtShapes.layer(state,layerId)
        if(layer.getString("kind")!="vector"||!ArtShapes.visible(state,layer)) return null
        val ids=ArtShapes.selected(state,layerId)
        if(ids.size!=1) return null
        return ArtShapes.items(layer).firstOrNull { it.getString("id")==ids[0]&&
            it.getString("kind")=="path"&&it.getBoolean("visible")&&it.getDouble("opacity")>0.0 }
    }
    private fun decorations(canvas:Canvas,nodes:List<ArtPathGeometry.Node>,m:Matrix,index:Int,locked:Boolean) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(locked) Color.GRAY else Color.rgb(95,190,255) }
        for(i in nodes.indices) {
            val p=screen(nodes[i].point,m);paint.style=Paint.Style.FILL
            val radius=if(i==index) 5f*density else 3f*density
            canvas.drawRect(p[0]-radius,p[1]-radius,p[0]+radius,p[1]+radius,paint)
        }
        if(index in nodes.indices) {
            val n=nodes[index];val p=screen(n.point,m)
            paint.color=if(locked) Color.GRAY else Color.rgb(255,135,100)
            for(handle in listOfNotNull(n.incoming,n.outgoing)) {
                val h=screen(handle,m);paint.strokeWidth=1f*density;paint.style=Paint.Style.STROKE
                canvas.drawLine(p[0],p[1],h[0],h[1],paint);paint.style=Paint.Style.FILL
                canvas.drawCircle(h[0],h[1],4f*density,paint)
            }
        }
    }
    fun draw(canvas:Canvas,state:JSONObject,document:String,revision:Int,layer:String,toScreen:Matrix,
        editing:Boolean,node:Int,closed:Boolean) {
        if(editing) {
            val shape=selected(state,layer)?:return
            val m=Matrix(toScreen).apply { preConcat(ArtShapes.matrix(shape.getJSONArray("matrix"))) }
            val p=editContext
            val active=p!=null&&p.getString("id")==shape.getString("id")&&
                p.getInt("expectedRevision")==revision
            val nodes=if(active) editNodes else ArtPathGeometry.nodes(shape)
            if(active) outline(canvas,nodes,editClosed,m)
            decorations(canvas,nodes,m,node.coerceIn(0,nodes.lastIndex),shape.getBoolean("locked")||
                ArtMenuOperations.isLocked(state,ArtShapes.layer(state,layer)))
        } else {
            val p=context?:return
            if(p.getString("documentId")!=document||p.getInt("expectedRevision")!=revision||
                p.getString("layerId")!=layer) return
            outline(canvas,draft,closed,toScreen)
            decorations(canvas,draft,toScreen,draft.lastIndex,false)
        }
    }
    private fun outline(canvas:Canvas,nodes:List<ArtPathGeometry.Node>,closed:Boolean,m:Matrix) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(70,180,240);style=Paint.Style.STROKE;strokeWidth=1.5f*density
        }
        val path=ArtPathGeometry.preview(nodes,closed);path.transform(m)
        canvas.drawPath(path,paint)
    }
    fun command(command:String,document:String,revision:Int,layer:String,closed:Boolean,
        commit:(JSONObject)->Unit) {
        if(command=="cancel") { cancel();return }
        val p=context?:error("请先在画布放置路径节点")
        checkContext(p,document,revision,layer)
        pressed=false
        if(command=="back") {
            if(draft.isNotEmpty()) draft.removeAt(draft.lastIndex)
            if(draft.isEmpty()) cancel() else view.invalidate()
            return
        }
        require(command=="finish"||command=="close")
        require(draft.size>=2) { "请至少放置两个节点" }
        val result=JSONObject(p.toString()).put("nodes",ArtPathGeometry.json(draft))
            .put("closed",command=="close"||closed)
        cancel();commit(result)
    }
    fun touch(e:MotionEvent,state:JSONObject,document:String,revision:Int,layerId:String,toScreen:Matrix,
        editing:Boolean,node:Int,nodeType:String,closed:Boolean,busy:Boolean,color:String,width:Float,
        opacity:Float,fill:Boolean,onNode:(Int)->Unit,onSelect:(JSONObject)->Unit,
        create:(JSONObject)->Unit,edit:(JSONObject)->Unit):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_CANCEL) { interrupt();return true }
        if(busy) { cancel();return true }
        val layer=ArtShapes.layer(state,layerId)
        require(layer.getString("kind")=="vector"&&ArtShapes.visible(state,layer)) { "请先选择可见矢量图层" }
        if(editing) return editTouch(e,state,document,revision,layerId,toScreen,node,onNode,onSelect,edit)
        context?.let { checkContext(it,document,revision,layerId) }
        require(!ArtMenuOperations.isLocked(state,layer)) { "矢量层或父组已锁定" }
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            if(e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                command("back",document,revision,layerId,closed,create);return true
            }
            frame=Matrix(toScreen);pressed=true;target=""
            if(draft.size>=2&&near(e,draft.first().point,toScreen)) target="close"
            else if(draft.size>=2&&e.eventTime-lastTap in 1L..300L&&near(e,draft.last().point,toScreen,16f))
                target="finish"
            else {
                require(draft.size<2048) { "绘制路径最多2048节点，请分段绘制" }
                if(context==null) context=JSONObject().put("documentId",document).put("expectedRevision",revision)
                    .put("layerId",layerId).put("style",JSONObject().put("fill",if(fill) color else "#00000000")
                        .put("stroke",color).put("strokeWidth",width.toDouble()).put("opacity",opacity.toDouble()))
                draft.add(ArtPathGeometry.Node(local(e,toScreen)))
            }
        }
        if(pressed&&target.isBlank()&&e.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            sameFrame(toScreen)
            val p=local(e,toScreen);val n=draft.last()
            if(!near(e,n.point,toScreen,4f)) {
                n.outgoing=p
                n.type=if(e.metaState and android.view.KeyEvent.META_ALT_MASK != 0) "corner" else nodeType
                if(n.type!="corner") n.incoming=n.point*2.0-p
            }
        }
        if(e.actionMasked==MotionEvent.ACTION_UP&&pressed) {
            pressed=false;lastTap=e.eventTime
            if(target.isNotBlank()) command(target,document,revision,layerId,closed,create)
            else if(e.metaState and android.view.KeyEvent.META_SHIFT_MASK != 0)
                command("finish",document,revision,layerId,closed,create)
        }
        view.invalidate();return true
    }
    private fun editTouch(e:MotionEvent,state:JSONObject,document:String,revision:Int,layer:String,m:Matrix,
        activeNode:Int,onNode:(Int)->Unit,onSelect:(JSONObject)->Unit,commit:(JSONObject)->Unit):Boolean {
        editContext?.let { checkContext(it,document,revision,layer) }
        val shape=selected(state,layer)
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            editContext=null
            if(shape==null) { selectAt(e,state,document,revision,layer,m,onNode,onSelect);return true }
            val matrix=Matrix(m).apply { preConcat(ArtShapes.matrix(shape.getJSONArray("matrix"))) }
            editNodes=ArtPathGeometry.nodes(shape);editClosed=shape.getBoolean("closed")
            editIndex=-1;editSide=""
            val active=activeNode.coerceIn(0,editNodes.lastIndex)
            if(active in editNodes.indices) {
                val n=editNodes[active]
                if(near(e,n.point,matrix,6f)) editIndex=active else {
                    if(n.incoming!=null&&near(e,n.incoming!!,matrix)) { editIndex=active;editSide="in" }
                    else if(n.outgoing!=null&&near(e,n.outgoing!!,matrix)) { editIndex=active;editSide="out" }
                }
            }
            if(editIndex<0) {
                editIndex=editNodes.indices.filter { near(e,editNodes[it].point,matrix) }
                    .minByOrNull { val p=screen(editNodes[it].point,matrix);hypot(e.x-p[0],e.y-p[1]) }?:-1
            }
            if(editIndex<0) { selectAt(e,state,document,revision,layer,m,onNode,onSelect);return true }
            onNode(editIndex);view.invalidate()
            if(shape.getBoolean("locked")||ArtMenuOperations.isLocked(state,ArtShapes.layer(state,layer))) return true
            editContext=JSONObject().put("documentId",document).put("expectedRevision",revision).put("layerId",layer)
                .put("id",shape.getString("id"))
            frame=matrix
            pointerStart=local(e,matrix)
            val n=editNodes[editIndex]
            grab=if(editSide=="in") requireNotNull(n.incoming) else if(editSide=="out") requireNotNull(n.outgoing) else n.point
        }
        val p=editContext?:return true
        if(e.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val current=shape?:error("路径选择已改变")
            require(current.getString("id")==p.getString("id"))
            val matrix=Matrix(m).apply { preConcat(ArtShapes.matrix(current.getJSONArray("matrix"))) };sameFrame(matrix)
            val point=grab+(local(e,matrix)-pointerStart)
            val action=JSONObject().put("action",if(editSide.isBlank()) "move_node" else "move_handle")
                .put("node",editIndex).put("x",point.x).put("y",point.y)
            if(editSide.isNotBlank()) action.put("side",editSide)
            editNodes=ArtPathGeometry.nodes(current);ArtPathGeometry.apply(editNodes,editClosed,action)
            if(e.actionMasked==MotionEvent.ACTION_UP) {
                val original=ArtPathGeometry.nodes(current)[editIndex]
                val old=if(editSide=="in") original.incoming else if(editSide=="out") original.outgoing else original.point
                editContext=null
                if(old!=null&&(point-old).length()>0.000001)
                    commit(JSONObject(p.toString()).put("edits",JSONArray().put(action)))
            }
        }
        view.invalidate();return true
    }
    private fun selectAt(e:MotionEvent,state:JSONObject,document:String,revision:Int,layer:String,m:Matrix,
        onNode:(Int)->Unit,onSelect:(JSONObject)->Unit) {
        val p=local(e,m);val unit=floatArrayOf(1f,0f);m.mapVectors(unit)
        val scale=hypot(unit[0],unit[1]);check(scale>0f)
        val l=ArtShapes.layer(state,layer)
        val id=ArtShapes.hit(l,p.x.toFloat(),p.y.toFloat(),(6f*density/scale).coerceAtMost(1000000f))
        val hit=id?.let { ArtShapes.items(l).first { s -> s.getString("id")==it } }
        require(hit==null||hit.getString("kind")=="path") { "请点选路径对象；基础形状请使用形状选择" }
        onNode(0)
        onSelect(JSONObject().put("documentId",document).put("expectedRevision",revision).put("layerId",layer)
            .put("ids",JSONArray(if(id==null) emptyList<String>() else listOf(id))))
    }
}
