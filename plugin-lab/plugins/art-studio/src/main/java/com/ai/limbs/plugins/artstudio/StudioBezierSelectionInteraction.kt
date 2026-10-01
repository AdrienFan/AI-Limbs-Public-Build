package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

internal class StudioBezierSelectionInteraction(private val view: View) {
    private var capture: JSONObject?=null
    private val draft=mutableListOf<ArtPathGeometry.Node>()
    private var frame=Matrix()
    private var pressed=false
    private var finish=false
    private var lastTap=0L
    private var editing: JSONObject?=null
    private var edited=mutableListOf<ArtPathGeometry.Node>()
    private var active=-1
    private var side=""
    private var grab=ArtPathGeometry.Vec(0.0,0.0)
    private var pointer=ArtPathGeometry.Vec(0.0,0.0)
    private val density get()=view.resources.displayMetrics.density
    val hasDraft get()=capture!=null
    fun cancel() {capture=null;draft.clear();pressed=false;finish=false;editing=null;edited.clear();view.invalidate()}
    private fun check(p: JSONObject,doc: String,revision: Int) {
        require(p.getString("documentId")==doc && p.getInt("expectedRevision")==revision) {"工程或选区已更新，请重新操作"}
    }
    private fun same(matrix: Matrix) {
        val a=FloatArray(9);val b=FloatArray(9);frame.getValues(a);matrix.getValues(b)
        require(a.indices.all {abs(a[it]-b[it])<0.0001f}) {"视图已改变，请重新操作当前节点"}
    }
    private fun local(e: MotionEvent,m: Matrix): ArtPathGeometry.Vec {
        val inverse=Matrix();require(m.invert(inverse))
        val p=floatArrayOf(e.x,e.y);inverse.mapPoints(p)
        require(p.all {it.isFinite() && abs(it)<=1_000_000}) {"选区坐标无效"}
        return ArtPathGeometry.Vec(p[0].toDouble(),p[1].toDouble())
    }
    private fun screen(v: ArtPathGeometry.Vec,m: Matrix)=floatArrayOf(v.x.toFloat(),v.y.toFloat()).also {m.mapPoints(it)}
    private fun near(e: MotionEvent,v: ArtPathGeometry.Vec,m: Matrix,r: Float=12f): Boolean {
        val p=screen(v,m);return hypot(e.x-p[0],e.y-p[1])<=r*density
    }
    fun command(command: String,doc: String,revision: Int,onCreate: (JSONObject)->Unit) {
        if(command=="cancel") {cancel();return}
        val p=capture ?: error("请先放置选区节点")
        check(p,doc,revision);pressed=false
        if(command=="back") {
            if(draft.isNotEmpty())draft.removeAt(draft.lastIndex)
            if(draft.isEmpty())cancel() else view.invalidate()
            return
        }
        require(command=="finish");require(draft.size>=2) {"请至少放置两个节点"}
        if(p.getBoolean("autoSmooth")) {
            for(i in draft.indices) {
                val node=draft[i]
                if(node.incoming==null && node.outgoing==null) {
                    val tangent=(draft[(i+1)%draft.size].point-draft[(i+draft.size-1)%draft.size].point)*(1.0/6.0)
                    node.incoming=node.point-tangent;node.outgoing=node.point+tangent;node.type="symmetric"
                }
            }
        }
        val result=JSONObject(p.toString()).put("nodes",ArtPathGeometry.json(draft));result.remove("autoSmooth")
        cancel();onCreate(result)
    }
    fun touch(e: MotionEvent,state: JSONObject,doc: String,revision: Int,m: Matrix,busy: Boolean,
        editMode: Boolean,component: Int,node: Int,mode: String,autoSmooth: Boolean,
        onNode: (Int)->Unit,onCreate: (JSONObject)->Unit,onEdit: (JSONObject)->Unit): Boolean {
        if(busy || e.actionMasked==MotionEvent.ACTION_CANCEL) {cancel();return true}
        if(editMode)return editTouch(e,state,doc,revision,m,component,node,onNode,onEdit)
        capture?.let {check(it,doc,revision)}
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            if(e.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {command("back",doc,revision,onCreate);return true}
            frame=Matrix(m);pressed=true;finish=false
            if(draft.size>=2 && (near(e,draft.first().point,m) ||
                (e.eventTime-lastTap in 1L..300L && near(e,draft.last().point,m,16f))))finish=true
            else {
                require(draft.size<ArtBezierSelection.MAX_NODES) {"曲线选区最多2048节点"}
                if(capture==null) {
                    val shift=e.metaState and KeyEvent.META_SHIFT_MASK!=0
                    val alt=e.metaState and KeyEvent.META_ALT_MASK!=0
                    val ctrl=e.metaState and KeyEvent.META_CTRL_MASK!=0
                    val chosen=when {shift&&alt->"intersect";ctrl->"replace";shift->"add";alt->"subtract";else->mode}
                    capture=JSONObject().put("documentId",doc).put("expectedRevision",revision)
                        .put("mode",chosen).put("autoSmooth",autoSmooth)
                }
                draft.add(ArtPathGeometry.Node(local(e,m)))
            }
        }
        if(pressed && !finish && e.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            same(m)
            val n=draft.last();val p=local(e,m)
            if(!near(e,n.point,m,4f)) {
                n.outgoing=p;n.type=if(e.metaState and KeyEvent.META_ALT_MASK!=0)"corner" else "symmetric"
                n.incoming=if(n.type=="corner")null else n.point*2.0-p
            }
        }
        if(e.actionMasked==MotionEvent.ACTION_UP && pressed) {
            pressed=false;lastTap=e.eventTime
            if(finish)command("finish",doc,revision,onCreate)
        }
        view.invalidate();return true
    }
    private fun editTouch(e: MotionEvent,state: JSONObject,doc: String,revision: Int,m: Matrix,
        component: Int,node: Int,onNode: (Int)->Unit,onEdit: (JSONObject)->Unit): Boolean {
        editing?.let {check(it,doc,revision);require(it.getInt("componentIndex")==component)}
        val selection=state.optJSONObject("selection") ?: error("当前没有曲线选区")
        val curve=ArtBezierSelection.component(selection,component);val nodes=ArtBezierSelection.nodes(curve)
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            editing=null;edited=nodes;side="";active=-1
            val chosen=node.coerceIn(0,nodes.lastIndex);val n=nodes[chosen]
            if(near(e,n.point,m,6f))active=chosen
            else if(n.incoming!=null && near(e,n.incoming!!,m)) {active=chosen;side="in"}
            else if(n.outgoing!=null && near(e,n.outgoing!!,m)) {active=chosen;side="out"}
            if(active<0)active=nodes.indices.filter {near(e,nodes[it].point,m)}
                .minByOrNull {i->val p=screen(nodes[i].point,m);hypot(e.x-p[0],e.y-p[1])} ?: -1
            if(active<0)return true
            onNode(active);frame=Matrix(m);pointer=local(e,m)
            grab=when(side) {"in"->requireNotNull(nodes[active].incoming);"out"->requireNotNull(nodes[active].outgoing);else->nodes[active].point}
            editing=JSONObject().put("documentId",doc).put("expectedRevision",revision).put("componentIndex",component)
        }
        val p=editing ?: return true
        if(e.actionMasked in setOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            same(m)
            val point=grab+(local(e,m)-pointer)
            val action=JSONObject().put("action",if(side.isEmpty())"move_node" else "move_handle")
                .put("node",active).put("x",point.x).put("y",point.y)
            if(side.isNotEmpty())action.put("side",side)
            edited=nodes;ArtPathGeometry.apply(edited,true,action)
            if(e.actionMasked==MotionEvent.ACTION_UP) {
                editing=null;edited.clear()
                if((point-grab).length()>0.000001)onEdit(JSONObject(p.toString()).put("edits",JSONArray().put(action)))
            }
        }
        view.invalidate();return true
    }
    fun draw(canvas: Canvas,state: JSONObject,doc: String,revision: Int,m: Matrix,editMode: Boolean,component: Int,node: Int) {
        val nodes: List<ArtPathGeometry.Node>
        val index: Int
        if(editMode) {
            val selection=state.optJSONObject("selection") ?: return
            val parts=ArtBezierSelection.parts(selection)
            if(component !in parts.indices || parts[component].getJSONObject("selection").optString("shape","rect")!="bezier")return
            val p=editing
            nodes=if(p!=null && p.getString("documentId")==doc && p.getInt("expectedRevision")==revision)edited
                else ArtBezierSelection.nodes(parts[component].getJSONObject("selection"))
            index=node.coerceIn(0,nodes.lastIndex)
        } else {
            val p=capture ?: return
            if(p.getString("documentId")!=doc || p.getInt("expectedRevision")!=revision)return
            nodes=draft;index=nodes.lastIndex
        }
        if(nodes.isEmpty())return
        val outline=ArtPathGeometry.preview(nodes,nodes.size>=2).apply {transform(m)}
        canvas.drawPath(outline,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(70,190,245)
            style=Paint.Style.STROKE;strokeWidth=1.5f*density})
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(70,190,245)}
        nodes.forEachIndexed {i,n->val p=screen(n.point,m);val r=(if(i==index)5 else 3)*density
            canvas.drawRect(p[0]-r,p[1]-r,p[0]+r,p[1]+r,paint)}
        if(index in nodes.indices) {
            val n=nodes[index];val p=screen(n.point,m);paint.color=Color.rgb(255,145,100)
            listOfNotNull(n.incoming,n.outgoing).forEach {v->val h=screen(v,m);paint.strokeWidth=density
                canvas.drawLine(p[0],p[1],h[0],h[1],paint);canvas.drawCircle(h[0],h[1],4*density,paint)}
        }
    }
}
