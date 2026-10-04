package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.ceil
import kotlinx.coroutines.*
import org.json.JSONObject

internal data class StudioAnimationFrame(val bitmap:Bitmap,val time:Int,
    val borrowed:StudioFrameCache.Lease<StudioRenderFrame>?=null) {
    fun release() { if(borrowed!=null)borrowed.close() else bitmap.recycle() }
}

/** Render one frame at a time; slow devices drop preview frames, never queue an unbounded backlog. */
@Composable internal fun StudioAnimationPlayback(store:ArtStore,frames:StudioFrameCache<StudioRenderFrame>,snapshot:JSONObject?,playing:Boolean,
    onStop:(Int)->Unit,onError:(Throwable)->Unit):StudioAnimationFrame? {
    var shown by remember {mutableStateOf<StudioAnimationFrame?>(null)}
    val stop by rememberUpdatedState(onStop)
    val error by rememberUpdatedState(onError)
    LaunchedEffect(playing,snapshot?.optString("id"),snapshot?.optInt("revision")) {
        if(!playing||snapshot==null)return@LaunchedEffect
        var pending:Bitmap?=null
        val cfg=ArtAnimation.settings(snapshot.getJSONObject("state"))
        val start=cfg.getInt("start");val end=cfg.getInt("end");val count=end-start+1
        val offset=cfg.getInt("current").takeIf {it in start..end}?.minus(start) ?: 0
        val origin=SystemClock.elapsedRealtime()
        var last=-1
        var lastExposure:List<Int>?=null
        var ready:StudioFrameCache.Lease<StudioRenderFrame>?=null
        try {
            ready=frames.acquire()
            val completed=ready?.frame
            val usable=completed!=null && completed.first.getString("id")==snapshot.getString("id") &&
                completed.first.getInt("revision")==snapshot.getInt("revision") &&
                ArtEditorPixels.playbackCanBorrow(snapshot)
            if(!usable) {ready?.close();ready=null}
            val initialExposure=ArtAnimation.layers(snapshot.getJSONObject("state")).map {
                ArtAnimation.active(it,cfg.getInt("current"))?.getInt("time") ?: -1
            }
            while(isActive) {
                val ticks=((SystemClock.elapsedRealtime()-origin)*cfg.getInt("fps")/1000).toInt()
                val step=offset+ticks
                if(!cfg.getBoolean("loop")&&step>=count) {stop(end);break}
                val time=start+step%count
                if(time!=last) {
                    val exposure=ArtAnimation.layers(snapshot.getJSONObject("state")).map {
                        ArtAnimation.active(it,time)?.getInt("time") ?: -1
                    }
                    val held=shown
                    if(held!=null && exposure==lastExposure) {
                        shown=held.copy(time=time)
                        last=time
                    } else if(held==null && ready!=null && exposure==initialExposure) {
                        // Playback of a static/held cel starts from the already displayed pixels.
                        shown=StudioAnimationFrame(requireNotNull(ready).frame.second,time,ready)
                        ready=null;last=time;lastExposure=exposure
                    } else {
                    ready?.close();ready=null
                    val retained=if(shown==null || shown?.borrowed!=null)1L else 2L
                    withContext(Dispatchers.IO) {
                        val frame=ArtAnimation.frame(snapshot,time)
                        val state=frame.getJSONObject("state");val w=state.getInt("width");val h=state.getInt("height")
                        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(store,state,w,h)+
                            ArtBrush.renderOverhead(state,w,h)+w.toLong()*h*4*retained,"动画播放与编辑画布")
                        pending=ArtRenderer.render(store,frame)
                    }
                    ensureActive()
                    val next=StudioAnimationFrame(requireNotNull(pending),time)
                    val old=shown;shown=next;pending=null;old?.release()
                    last=time
                    lastExposure=exposure
                    }
                }
                delay(maxOf(1L,1000L/cfg.getInt("fps")-((SystemClock.elapsedRealtime()-origin)%maxOf(1L,1000L/cfg.getInt("fps")))))
            }
        } catch(cancel:CancellationException) {throw cancel}
        catch(failure:Throwable) {error(failure)}
        finally {pending?.recycle();shown?.release();shown=null;ready?.close()}
    }
    return shown
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun StudioAnimationTimeline(snapshot:JSONObject,busy:Boolean,playing:Boolean,previewFrame:Int?,
    onPlay:(Boolean)->Unit,onChange:(String,JSONObject)->Unit,onExport:(Int)->Unit) {
    val data=remember(snapshot) { ArtAnimation.describe(snapshot) }
    val state=snapshot.getJSONObject("state")
    val time=data.getInt("current")
    val tracks=data.getJSONArray("tracks")
    val selected=state.optString("selectedLayerId")
    val track=(0 until tracks.length()).map {tracks.getJSONObject(it)}.firstOrNull {it.getString("id")==selected}
    val keyTimes=track?.getJSONArray("keyframes")?.let {list->(0 until list.length()).map {list.getInt(it)}} ?: emptyList()
    val currentKey=track?.opt("activeKeyframe")?.takeIf {it!=JSONObject.NULL} as? Int
    val enabled=!busy&&!playing
    val horizontal=rememberScrollState()
    var frameInput by remember(time,snapshot.getString("id")) {mutableStateOf(time.toString())}
    var settingsDialog by remember {mutableStateOf(false)}
    var moveDialog by remember {mutableStateOf(false)}
    var disableDialog by remember {mutableStateOf(false)}
    var exportDialog by remember {mutableStateOf(false)}
    fun request()=JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision"))
    fun seek(frame:Int)=onChange("ANIMATION_TIME",request().put("frame",frame))
    fun key(action:String,extra:JSONObject=JSONObject()) {
        val p=request().put("layerId",selected).put("frame",time).put("action",action)
        extra.keys().forEach {p.put(it,extra.get(it))}
        onChange("ANIMATION_KEY",p)
    }
    Column(Modifier.fillMaxSize().clipToBounds()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            TextButton(enabled=!busy,onClick={onPlay(!playing)}) {Text(if(playing)"暂停" else "播放")}
            TextButton(enabled=enabled&&time>0,onClick={seek(time-1)}) {Text("上一帧")}
            TextButton(enabled=enabled&&time<ArtAnimation.MAX_TIME,onClick={seek(time+1)}) {Text("下一帧")}
            TextButton(enabled=enabled&&keyTimes.any {it<time},onClick={seek(keyTimes.filter {it<time}.max())}) {Text("上一关键帧")}
            TextButton(enabled=enabled&&keyTimes.any {it>time},onClick={seek(keyTimes.filter {it>time}.min())}) {Text("下一关键帧")}
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(frameInput,{frameInput=it},Modifier.weight(1f),label={Text("帧号 0–9999")},singleLine=true,enabled=enabled)
            TextButton(enabled=enabled&&frameInput.toIntOrNull()?.let {it in 0..ArtAnimation.MAX_TIME}==true,
                onClick={seek(frameInput.toInt())}) {Text("定位")}
        }
        Text(if(playing)"播放第 ${previewFrame ?: time} 帧 · ${data.getInt("fps")} fps"
            else "编辑第 $time 帧"+if(currentKey!=null)" · 内容来自关键帧 $currentKey" else " · 静态图层",
            style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled=enabled&&track!=null&&time !in keyTimes,onClick={key("blank")}) {Text("空白帧")}
            TextButton(enabled=enabled&&track!=null&&time !in keyTimes,onClick={key("duplicate")}) {Text("复制帧")}
            TextButton(enabled=enabled&&time>0&&time in keyTimes,onClick={key("remove")}) {Text("删除帧")}
            TextButton(enabled=enabled&&time>0&&time in keyTimes,onClick={moveDialog=true}) {Text("移动帧")}
            TextButton(enabled=enabled&&keyTimes.isNotEmpty(),onClick={disableDialog=true}) {Text("停用轨道")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            TextButton(enabled=enabled,onClick={settingsDialog=true}) {Text("范围/帧率")}
            FilterChip(selected=data.getBoolean("loop"),enabled=enabled,onClick={
                onChange("ANIMATION_SETTINGS",request().put("loop",!data.getBoolean("loop")))
            },label={Text("循环")})
            FilterChip(selected=data.getBoolean("onion"),enabled=enabled,onClick={
                onChange("ANIMATION_SETTINGS",request().put("onion",!data.getBoolean("onion")))
            },label={Text("洋葱皮")})
            TextButton(enabled=enabled,onClick={exportDialog=true}) {Text("导出GIF")}
        }
        HorizontalDivider()
        Row {
            Text("图层 / 帧",Modifier.width(88.dp).padding(6.dp),style=MaterialTheme.typography.labelSmall)
            StudioTimelineStrip(horizontal,data.getInt("start"),data.getInt("end"),Modifier.weight(1f)) { frame ->
                Text(frame.toString(),Modifier.width(40.dp).padding(5.dp),style=MaterialTheme.typography.labelSmall)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            items(tracks.length(),key={tracks.getJSONObject(it).getString("id")}) {index->
                val row=tracks.getJSONObject(index)
                val times=row.getJSONArray("keyframes").let {list->(0 until list.length()).map {list.getInt(it)}.toSet()}
                Row(Modifier.fillMaxWidth().background(if(row.getString("id")==selected)
                    MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Text(row.getString("name"),Modifier.width(88.dp).clickable(enabled=enabled) {
                        onChange("LAYER_SELECT",request().put("id",row.getString("id")))
                    }.padding(6.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    StudioTimelineStrip(horizontal,data.getInt("start"),data.getInt("end"),Modifier.weight(1f)) { frame ->
                        Box(
                            Modifier.width(40.dp).height(38.dp)
                                .background(if(frame==(previewFrame ?: time))MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable(enabled=enabled) {seek(frame)},
                            contentAlignment=Alignment.Center) {
                            Text(if(frame in times)"●" else if(times.isNotEmpty())"—" else "·",
                                color=if(frame in times)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Text("● 关键帧  — 保持上一帧 · 静态层\n在保持区绘画会修改其来源关键帧；需要新画面先建空白或复制帧。",
            style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(6.dp))
    }
    if(settingsDialog) {
        var fps by remember {mutableStateOf(data.getInt("fps").toString())}
        var start by remember {mutableStateOf(data.getInt("start").toString())}
        var end by remember {mutableStateOf(data.getInt("end").toString())}
        val f=fps.toIntOrNull();val s=start.toIntOrNull();val e=end.toIntOrNull()
        val valid=f!=null&&f in 1..60&&s!=null&&e!=null&&s in 0..ArtAnimation.MAX_TIME&&e in s..ArtAnimation.MAX_TIME&&e-s<600
        AlertDialog(onDismissRequest={settingsDialog=false},title={Text("动画播放设置")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(fps,{fps=it},label={Text("帧率 1–60")},singleLine=true)
                OutlinedTextField(start,{start=it},label={Text("起始帧")},singleLine=true)
                OutlinedTextField(end,{end=it},label={Text("结束帧（含，最多600帧）")},singleLine=true)
            }},confirmButton={TextButton(enabled=valid&&enabled,onClick={
                onChange("ANIMATION_SETTINGS",request().put("fps",f).put("start",s).put("end",e));settingsDialog=false
            }){Text("应用")}},dismissButton={TextButton(onClick={settingsDialog=false}){Text("取消")}})
    }
    if(moveDialog) {
        var target by remember {mutableStateOf((time+1).toString())}
        val t=target.toIntOrNull()
        AlertDialog(onDismissRequest={moveDialog=false},title={Text("移动关键帧")},
            text={OutlinedTextField(target,{target=it},label={Text("目标帧（不覆盖已有帧）")},singleLine=true)},
            confirmButton={TextButton(enabled=enabled&&t!=null&&t in 1..ArtAnimation.MAX_TIME&&t !in keyTimes,
                onClick={key("move",JSONObject().put("targetFrame",t));moveDialog=false}){Text("移动")}},
            dismissButton={TextButton(onClick={moveDialog=false}){Text("取消")}})
    }
    if(disableDialog)AlertDialog(onDismissRequest={disableDialog=false},title={Text("停用动画轨道？")},
        text={Text("保留当前帧为可编辑静态图层，移除该图层其他关键帧。可以撤销。")},
        confirmButton={TextButton(enabled=enabled,onClick={key("disable");disableDialog=false}){Text("停用")}},
        dismissButton={TextButton(onClick={disableDialog=false}){Text("取消")}})
    if(exportDialog) {
        var edge by remember {mutableStateOf("512")}
        val value=edge.toIntOrNull()
        AlertDialog(onDismissRequest={exportDialog=false},title={Text("导出GIF")},
            text={Column {
                Text("使用播放范围与帧率，255色加透明；半透明以alpha 128为界。洋葱皮不导出。")
                OutlinedTextField(edge,{edge=it},label={Text("最大边 64–1024像素")},singleLine=true)
            }},confirmButton={TextButton(enabled=enabled&&value!=null&&value in 64..1024,
                onClick={onExport(value!!);exportDialog=false}){Text("导出")}},
            dismissButton={TextButton(onClick={exportDialog=false}){Text("取消")}})
    }
}

/** Compose visible columns only, while every track retains the same full scroll extent. */
@Composable private fun StudioTimelineStrip(scroll:ScrollState,start:Int,end:Int,modifier:Modifier,
    cell:@Composable (Int)->Unit) {
    BoxWithConstraints(modifier) {
        val density=LocalDensity.current
        val widthPx=with(density) {40.dp.toPx()}
        val viewportPx=with(density) {maxWidth.toPx()}
        val first=(start+(scroll.value/widthPx).toInt()-1).coerceIn(start,end)
        val last=(first+ceil(viewportPx/widthPx).toInt()+2).coerceAtMost(end)
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Spacer(Modifier.width((40*(first-start)).dp))
            for(frame in first..last) cell(frame)
            Spacer(Modifier.width((40*(end-last)).dp))
        }
    }
}
