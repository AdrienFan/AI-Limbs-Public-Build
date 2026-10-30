package com.ai.limbs.plugins.artstudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Closed paths remain editable drafts; only explicit confirmation commits a selection. */
internal class StudioMagneticSelectionInteraction(private val view: View) {
    private var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var job: Job?=null
    private var serial=0
    private var capture: JSONObject?=null
    private var frozenImage: ArtMagneticSelection.Image?=null
    private var source: ArtMagneticSelection.Image?=null
    private var matrix=Matrix()
    private var inverse=Matrix()
    private val anchors=mutableListOf<ArtMagneticSelection.Point>()
    private var cache=ConcurrentHashMap<Pair<ArtMagneticSelection.Point,ArtMagneticSelection.Point>,List<ArtMagneticSelection.Point>>()
    private var points=emptyList<ArtMagneticSelection.Point>()
    private var editing=-1
    private var dragged=false
    private var downX=0f
    private var downY=0f
    private var finishing=false
    private var closedDraft=false
    private var closedReady=false
    private var lastPreview=0L
    private var commit: (JSONObject)->Unit = {}
    var onDraft: (Boolean)->Unit = {}
    val hasDraft get()=capture!=null
    fun source(image: ArtMagneticSelection.Image?) {
        if(source!==image) {cancel();source=image}
    }
    fun cancel() {val existed=hasDraft;serial++;job?.cancel();job=null;capture=null;frozenImage=null;anchors.clear();cache=ConcurrentHashMap();points=emptyList();editing=-1;finishing=false;closedDraft=false;closedReady=false;if(existed)onDraft(false);view.invalidate()}
    fun dispose() {cancel();scope.cancel();source=null}
    private fun same(m: Matrix): Boolean {
        val a=FloatArray(9);val b=FloatArray(9);m.getValues(a);matrix.getValues(b)
        return a.indices.all {abs(a[it]-b[it])<0.001f}
    }
    private fun document(x: Float,y: Float): ArtMagneticSelection.Point {
        val v=floatArrayOf(x,y);inverse.mapPoints(v)
        return ArtMagneticSelection.Point(v[0].toDouble(),v[1].toDouble())
    }
    private fun screen(q: ArtMagneticSelection.Point): FloatArray =
        floatArrayOf(q.x.toFloat(),q.y.toFloat()).also {matrix.mapPoints(it)}
    private fun inBounds(q: ArtMagneticSelection.Point)=frozenImage!!.rect.contains(floor(q.x).toInt(),floor(q.y).toInt())
    private fun request(closed: Boolean,cursor: ArtMagneticSelection.Point?=null) {
        val p=capture ?: return;val image=frozenImage ?: return
        if(closed)require(anchors.size>=3) {"至少放置三个锚点"}
        val guide=anchors.toMutableList()
        if(cursor!=null && inBounds(cursor) && guide.size<ArtMagneticSelection.MAX_ANCHORS &&
            hypot(cursor.x-guide.last().x,cursor.y-guide.last().y)>=0.1)guide.add(cursor)
        val settings=JSONObject(p.toString());val segmentCache=cache;val requestId=++serial;job?.cancel()
        if(!scope.isActive)scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        finishing=closed
        closedDraft=closed;closedReady=false
        job=scope.launch {
            try {
                val owner=coroutineContext[Job]!!
                val result=ArtMagneticSelection.trace(image,guide,settings,closed,{!owner.isActive},segmentCache)
                ensureActive()
                view.post {
                    if(serial==requestId && capture!=null) {
                        points=result;finishing=false;closedReady=closed;view.invalidate()
                    }
                }
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {
                android.util.Log.e("ArtStudio","Magnetic live-wire search failed",error)
                view.post {if(serial==requestId && capture!=null) {
                    points=emptyList();finishing=false;view.invalidate()
                    Toast.makeText(view.context,error.message,Toast.LENGTH_SHORT).show()
                }}
            }
        }
    }
    private fun confirm() {
        val settings=capture ?: error("请先放置磁性锚点")
        require(closedDraft && closedReady && !finishing) {"请先闭合路径并等待边缘计算完成，再确认选区"}
        val resolved=JSONObject(settings.toString()).put("points",ArtMagneticSelection.json(points))
            .put("anchors",ArtMagneticSelection.json(anchors))
        val callback=commit;cancel();callback(resolved)
    }
    /** Hit actual cached live-wire segments, rather than straight chords between anchors. */
    private fun insertClosed(q:ArtMagneticSelection.Point,x:Float,y:Float) {
        require(closedReady && anchors.size<ArtMagneticSelection.MAX_ANCHORS) {"请等待闭合路径，锚点最多128个"}
        var best=Double.POSITIVE_INFINITY;var insert=-1
        for(i in anchors.indices) {
            val segment=requireNotNull(cache[anchors[i] to anchors[(i+1)%anchors.size]]) {"闭合边缘缓存已失效，请重新计算"}
            for(k in 0 until segment.lastIndex) {
                val a=screen(segment[k]);val b=screen(segment[k+1]);val dx=(b[0]-a[0]).toDouble();val dy=(b[1]-a[1]).toDouble()
                val length=dx*dx+dy*dy
                val t=if(length==0.0)0.0 else (((x-a[0])*dx+(y-a[1])*dy)/length).coerceIn(0.0,1.0)
                val d=hypot(x-a[0]-t*dx,y-a[1]-t*dy)
                if(d<best) {best=d;insert=i+1}
            }
        }
        require(insert>=0 && best<=20*view.resources.displayMetrics.density) {"请点击闭合边缘附近以插入锚点"}
        anchors.add(insert,q);editing=insert;cache.clear();request(true)
    }
    fun reopen(saved:JSONObject,documentId:String,revision:Int,layerId:String,m:Matrix,onCommit:(JSONObject)->Unit) {
        require(!hasDraft) {"请先完成或取消当前路径"}
        val image=source ?: error("磁性参考正在准备，请稍候")
        val o=ArtMagneticSelection.options(saved)
        require(image.documentId==documentId && image.revision==revision && image.layerId==layerId &&
            image.referenceKey==ArtMagneticSelection.referenceKey(o) && image.filterRadius==o.getInt("filterRadius")) {"请先载入原参数，等待参考准备完成"}
        val restored=ArtMagneticSelection.anchors(saved.getJSONArray("anchors"))
        require(restored.size>=3 && restored.all {image.rect.contains(floor(it.x).toInt(),floor(it.y).toInt())}) {"保存的锚点不在当前参考范围"}
        require(m.invert(inverse));matrix=Matrix(m);frozenImage=image
        capture=o.put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId).put("reedit",true)
        anchors.addAll(restored);commit=onCommit;onDraft(true);request(true)
    }
    fun command(action: String) {
        when(action) {
            "cancel"->cancel()
            "back"->{if(anchors.isNotEmpty())anchors.removeAt(anchors.lastIndex);cache.clear();if(anchors.isEmpty())cancel() else request(closedDraft && anchors.size>=3)}
            "close"->request(true)
            "apply"->confirm()
            "finish"->if(closedDraft)confirm() else request(true)
            else->error("未知磁性套索命令")
        }
    }
    fun touch(event: MotionEvent,documentId: String,revision: Int,layerId: String,m: Matrix,busy: Boolean,
        options: JSONObject,onCommit: (JSONObject)->Unit): Boolean {
        if(busy || event.actionMasked==MotionEvent.ACTION_CANCEL || event.buttonState and MotionEvent.BUTTON_SECONDARY!=0) {cancel();return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN && capture==null) {
            val image=source ?: error("磁性参考正在准备，请稍候")
            require(image.documentId==documentId && image.revision==revision && image.layerId==layerId) {"参考已变化，请等待重新准备"}
            val o=ArtMagneticSelection.options(options)
            require(image.referenceKey==ArtMagneticSelection.referenceKey(o) && image.filterRadius==o.getInt("filterRadius"))
            require(m.invert(inverse));matrix=Matrix(m);frozenImage=image
            // MotionEvent exposes modifier bits via metaState, not KeyEvent convenience properties.
            val shift=event.metaState and KeyEvent.META_SHIFT_MASK!=0
            val alt=event.metaState and KeyEvent.META_ALT_MASK!=0
            val ctrl=event.metaState and KeyEvent.META_CTRL_MASK!=0
            val mode=when {shift && alt->"intersect";ctrl->"replace"
                shift->"add";alt->"subtract";else->o.getString("mode")}
            capture=o.put("documentId",documentId).put("expectedRevision",revision).put("layerId",layerId).put("mode",mode)
            commit=onCommit;onDraft(true)
        }
        val p=capture ?: return true
        require(p.getString("documentId")==documentId && p.getInt("expectedRevision")==revision &&
            p.getString("layerId")==layerId && same(m)) {"工程或视图已变化，请重新放置锚点"}
        val q=document(event.x,event.y)
        fun append() {
            require(inBounds(q)) {"锚点需要位于参考范围内"}
            require(anchors.size<ArtMagneticSelection.MAX_ANCHORS) {"磁性套索最多128锚点"}
            if(anchors.isEmpty() || hypot(q.x-anchors.last().x,q.y-anchors.last().y)>=0.1)anchors.add(q)
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                require(inBounds(q));downX=event.x;downY=event.y;dragged=false
                editing=anchors.indices.minByOrNull {i->val xy=screen(anchors[i]);hypot(xy[0]-event.x,xy[1]-event.y)}
                    ?.takeIf {i->val xy=screen(anchors[i]);hypot(xy[0]-event.x,xy[1]-event.y)<=12*view.resources.displayMetrics.density} ?: -1
                if(editing<0) {
                    if(closedDraft) {
                        if(!closedReady)return true
                        insertClosed(q,event.x,event.y)
                    } else {append();request(false)}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if(hypot(event.x-downX,event.y-downY)>4f)dragged=true
                if(editing>=0) {
                    if(inBounds(q)){anchors[editing]=q;cache.clear();request(closedDraft)}
                } else if(!closedDraft && inBounds(q)) {
                    val last=screen(anchors.last())
                    if(hypot(last[0]-event.x,last[1]-event.y)>=p.getInt("anchorGap")){append();request(false)}
                    else if(android.os.SystemClock.uptimeMillis()-lastPreview>=80){lastPreview=android.os.SystemClock.uptimeMillis();request(false,q)}
                }
            }
            MotionEvent.ACTION_UP -> {
                if(editing==0 && !dragged && anchors.size>=3){
                    if(closedDraft) {if(closedReady)confirm()} else request(true)
                    return true
                }
                if(editing>=0 && !inBounds(q)) {anchors.removeAt(editing);cache.clear()}
                else if(!closedDraft && editing<0 && dragged && inBounds(q))append()
                editing=-1
                if(anchors.isEmpty())cancel() else request(closedDraft && anchors.size>=3)
            }
            MotionEvent.ACTION_HOVER_MOVE -> if(!closedDraft && inBounds(q) && android.os.SystemClock.uptimeMillis()-lastPreview>=80) {
                lastPreview=android.os.SystemClock.uptimeMillis();request(false,q)
            }
        }
        view.invalidate();return true
    }
    fun draw(canvas: Canvas) {
        if(capture==null)return
        val path=Path()
        points.forEachIndexed {i,q->val v=screen(q);if(i==0)path.moveTo(v[0],v[1]) else path.lineTo(v[0],v[1])}
        if(closedDraft && closedReady)path.close()
        canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(80,210,230);style=Paint.Style.STROKE;strokeWidth=2f*view.resources.displayMetrics.density})
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(245,180,65)}
        anchors.forEachIndexed {i,q->val v=screen(q);canvas.drawCircle(v[0],v[1],(if(i==0)6f else 4f)*view.resources.displayMetrics.density,paint)}
    }
}
