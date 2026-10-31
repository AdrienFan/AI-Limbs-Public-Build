package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.*

/** The graph edits the same JSON sent by AI callers; it never substitutes a different curve model. */
@Composable
internal fun StudioFilterCurves(key:String,text:String,interpolation:String,onChange:(String)->Unit) {
    var channel by remember(key) {mutableStateOf("master")}
    var ruleIndex by remember(key) {mutableIntStateOf(0)}
    var selected by remember(key,channel,ruleIndex) {mutableIntStateOf(-1)}
    var error:String?=null
    var curves:JSONObject?=null
    var rules:JSONArray?=null
    var points:JSONArray?=null
    try {
        if(key=="curves") {
            curves=JSONObject(text)
            if(curves.has(channel))points=curves.getJSONArray(channel)
        } else {
            rules=JSONArray(text)
            if(ruleIndex<rules.length())points=rules.getJSONObject(ruleIndex).getJSONArray("points")
        }
        points?.let {ArtFilterCurve(it,interpolation)}
    } catch(e:JSONException) {error="先修正JSON格式：${e.message}"}
    catch(e:IllegalArgumentException) {error="先修正曲线节点：${e.message}"}
    val objectConfig=curves;val arrayConfig=rules;val graphPoints=points
    if(error!=null) {Text(error);return}
    fun replace(next:JSONArray) {
        if(key=="curves")onChange(requireNotNull(objectConfig).put(channel,next).toString())
        else {
            val a=requireNotNull(arrayConfig)
            a.getJSONObject(ruleIndex).put("points",next);onChange(a.toString())
        }
    }
    if(key=="curves") {
        for(group in listOf("master","red","green","blue","alpha").chunked(3))
            Row {for(name in group) TextButton(onClick={channel=name}) {Text((if(channel==name)"✓" else "")+name)}}
        if(graphPoints==null) TextButton(onClick={replace(JSONArray("[[0,0],[1,1]]"))}) {Text("启用 $channel 曲线")}
    } else {
        val a=requireNotNull(arrayConfig)
        if(a.length()>0) {
            Row {
                TextButton(enabled=ruleIndex>0,onClick={ruleIndex--}) {Text("上一条")}
                Text("规则 ${ruleIndex+1}/${a.length()}")
                TextButton(enabled=ruleIndex<a.length()-1,onClick={ruleIndex++}) {Text("下一条")}
            }
            if(ruleIndex<a.length()) {
                val rule=a.getJSONObject(ruleIndex)
                Text("${rule.optString("driver")} → ${rule.optString("target")}；通道名可在JSON中编辑")
            }
        }
        Row {
            TextButton(enabled=a.length()<16,onClick={
                a.put(JSONObject().put("driver","red").put("target","green").put("points",JSONArray("[[0,0.5],[1,0.5]]")))
                ruleIndex=a.length()-1;onChange(a.toString())
            }) {Text("新增规则")}
            TextButton(enabled=ruleIndex<a.length(),onClick={
                a.remove(ruleIndex);ruleIndex=min(ruleIndex,max(0,a.length()-1));onChange(a.toString())
            }) {Text("删除规则")}
        }
    }
    if(graphPoints==null)return
    if((0 until graphPoints.length()).any {graphPoints.getJSONArray(it).getDouble(1) !in 0.0..1.0}) {
        Text("曲线含0–1外的输出值，请使用下方JSON编辑，图形不会裁掉这些节点。")
        return
    }
    androidx.compose.runtime.key(key,channel,ruleIndex) {
        AndroidView(factory={CurveView(it)},update={view ->
            view.bind(graphPoints,interpolation,selected,{selected=it},::replace)
        },modifier=Modifier.fillMaxWidth().height(160.dp))
    }
    Text("横轴输入、纵轴输出；点按添加，拖动调整。跨通道曲线的0.5水平线表示不调整。")
    Row {
        TextButton(enabled=selected in 1 until graphPoints.length()-1,onClick={
            val next=JSONArray(graphPoints.toString());next.remove(selected);selected=-1;replace(next)
        }) {Text("删除节点")}
        TextButton(onClick={selected=-1;replace(JSONArray(if(key=="rules")"[[0,0.5],[1,0.5]]" else "[[0,0],[1,1]]"))}) {Text("重置为中性")}
    }
}

private class CurveView(context:Context):View(context) {
    private var points=JSONArray("[[0,0],[1,1]]")
    private var interpolation="smooth"
    private var selected=-1
    private var select:(Int)->Unit={}
    private var change:(JSONArray)->Unit={}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val inset=12*resources.displayMetrics.density
    private var dragging=false
    fun bind(p:JSONArray,mode:String,index:Int,onSelect:(Int)->Unit,onChange:(JSONArray)->Unit) {
        points=JSONArray(p.toString());interpolation=mode;selected=index;select=onSelect;change=onChange;invalidate()
    }
    private fun px(x:Double)=inset+x.toFloat()*max(1f,width-2*inset)
    private fun py(y:Double)=height-inset-y.toFloat()*max(1f,height-2*inset)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density;paint.color=Color.GRAY
        for(i in 0..4) {
            val t=i/4.0;canvas.drawLine(px(t),py(0.0),px(t),py(1.0),paint)
            canvas.drawLine(px(0.0),py(t),px(1.0),py(t),paint)
        }
        val curve=ArtFilterCurve(points,interpolation)
        val path=Path()
        for(i in 0..128) {val x=i/128.0;val y=curve.at(x).coerceIn(0.0,1.0)
            if(i==0)path.moveTo(px(x),py(y)) else path.lineTo(px(x),py(y))}
        paint.color=Color.rgb(90,190,245);paint.strokeWidth=2*resources.displayMetrics.density;canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL
        for(i in 0 until points.length()) {
            paint.color=if(i==selected)Color.YELLOW else Color.WHITE
            val p=points.getJSONArray(i);canvas.drawCircle(px(p.getDouble(0)),py(p.getDouble(1)),4*resources.displayMetrics.density,paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true);dragging=true
                var best=-1;var distance=24*resources.displayMetrics.density
                for(i in 0 until points.length()) {
                    val p=points.getJSONArray(i);val d=hypot(px(p.getDouble(0))-event.x,py(p.getDouble(1))-event.y)
                    if(d<distance) {best=i;distance=d}
                }
                if(best<0 && points.length()<32) {
                    val x=((event.x-inset)/max(1f,width-2*inset)).toDouble().coerceIn(0.0,1.0)
                    val y=((height-inset-event.y)/max(1f,height-2*inset)).toDouble().coerceIn(0.0,1.0)
                    var index=1;while(index<points.length()-1 && points.getJSONArray(index).getDouble(0)<x)index++
                    if(x-points.getJSONArray(index-1).getDouble(0)>=.002 && points.getJSONArray(index).getDouble(0)-x>=.002) {
                        val next=JSONArray()
                        for(i in 0 until points.length()) {if(i==index)next.put(JSONArray().put(x).put(y));next.put(points.getJSONArray(i))}
                        points=next;best=index;change(JSONArray(points.toString()))
                    }
                }
                selected=best;select(best);invalidate();return true
            }
            MotionEvent.ACTION_MOVE -> if(dragging && selected in 0 until points.length()) {
                val p=points.getJSONArray(selected)
                if(selected in 1 until points.length()-1) {
                    val low=points.getJSONArray(selected-1).getDouble(0)+.000001
                    val high=points.getJSONArray(selected+1).getDouble(0)-.000001
                    // Extremely close JSON nodes remain editable as JSON; do not invent a different ordering.
                    if(low<=high)p.put(0,((event.x-inset)/max(1f,width-2*inset)).toDouble().coerceIn(low,high))
                }
                p.put(1,((height-inset-event.y)/max(1f,height-2*inset)).toDouble().coerceIn(0.0,1.0))
                change(JSONArray(points.toString()));invalidate();return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                dragging=false;parent.requestDisallowInterceptTouchEvent(false)
                if(event.actionMasked==MotionEvent.ACTION_UP)performClick()
                return true
            }
        }
        return dragging
    }
    override fun performClick():Boolean {super.performClick();return true}
}
