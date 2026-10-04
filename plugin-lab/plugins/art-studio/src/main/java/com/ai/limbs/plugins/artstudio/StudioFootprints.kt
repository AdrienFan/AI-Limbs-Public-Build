package com.ai.limbs.plugins.artstudio

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rows are lightweight projections, not copies of native geometry or operation payloads. */
@Composable internal fun StudioFootprints(snapshot: JSONObject, busy: Boolean,
    onGoto: (String, Int) -> Unit, onDelete: (String, String, Int) -> Unit) {
    val documentId=snapshot.getString("id")
    val timeline=snapshot.getJSONArray("timeline")
    val position=snapshot.getInt("timelinePosition")
    val revision=snapshot.getInt("revision")
    val stats=snapshot.getJSONObject("historyStats")
    val scroll=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val timeFormat=remember {SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault())}
    var detail by remember(documentId) {mutableStateOf<JSONObject?>(null)}
    var showBranches by remember(documentId) {mutableStateOf(false)}
    var selectedId by remember(documentId) {mutableStateOf<String?>(null)}
    val selectedIndex=(0 until timeline.length()).firstOrNull {
        timeline.getJSONObject(it).getString("id")==selectedId
    }
    val selected=selectedIndex?.let {timeline.getJSONObject(it)}
    var initial by remember(documentId) {mutableStateOf(true)}
    var previousPosition by remember(documentId) {mutableIntStateOf(position)}
    LaunchedEffect(documentId,revision) {
        // Keep following the current state only while the reader has not scrolled away.
        val following=initial||scroll.layoutInfo.visibleItemsInfo.any {it.index==previousPosition}
        if(following&&!scroll.isScrollInProgress)scroll.scrollToItem(position)
        previousPosition=position
        initial=false
        if(selected==null)selectedId=null
    }
    fun actor(step: JSONObject)=when(val value=step.getString("actor")) {
        "AWEI"->"阿伟";"LANER"->"兰儿";else->value
    }
    fun time(step: JSONObject): String {
        val timestamp=step.optLong("timestamp")
        return if(timestamp>0)timeFormat.format(Date(timestamp)) else "初始状态"
    }
    Column(Modifier.fillMaxSize().clipToBounds()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("当前 $position / ${stats.getInt("reachableSteps")}步",Modifier.weight(1f),
                style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={scope.launch {scroll.scrollToItem(position)}}) {Text("定位")}
        }
        if(stats.getInt("otherBranchSteps")>0)
            TextButton(onClick={showBranches=true}) {
                Text("查看其他分支 · ${stats.getInt("otherBranchSteps")}步",style=MaterialTheme.typography.labelSmall)
            }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clipToBounds(),state=scroll) {
            items(timeline.length(),key={timeline.getJSONObject(it).getString("id")}) {index->
                val step=timeline.getJSONObject(index)
                val current=index==position
                val future=index>position
                val label=step.getString("label")
                val selectedForDelete=step.getString("id")==selectedId
                Row(Modifier.fillMaxWidth()
                    .background(when {
                        selectedForDelete->MaterialTheme.colorScheme.secondaryContainer
                        current->MaterialTheme.colorScheme.surfaceVariant
                        else->MaterialTheme.colorScheme.surface
                    })
                    .clickable(enabled=!busy&&!current,onClickLabel="切换到第$index 步：$label") {
                        onGoto(step.getString("id"),revision)
                    }.padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    // Checkbox consumes its own tap; selecting deletion must never invoke history.goto.
                    Checkbox(checked=selectedForDelete,enabled=!busy&&index>0,
                        onCheckedChange={checked->selectedId=if(checked)step.getString("id") else null},
                        modifier=Modifier.size(40.dp).semantics {contentDescription="选中第$index 步供单笔删除：$label"})
                    Text(if(current)"●" else "○",
                        color=if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.padding(end=6.dp))
                    if(step.has("color"))Box(Modifier.padding(end=6.dp).size(10.dp)
                        .background(androidx.compose.ui.graphics.Color(Color.parseColor(step.getString("color"))),
                            androidx.compose.foundation.shape.CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text("$index · $label",maxLines=2,overflow=TextOverflow.Ellipsis,
                            color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(future).55f else 1f))
                        if(step.getString("summary").isNotBlank())
                            Text(step.getString("summary"),maxLines=2,overflow=TextOverflow.Ellipsis,
                                style=MaterialTheme.typography.labelSmall)
                        Text(actor(step)+" · "+time(step),style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick={detail=step}) {Text("详情",style=MaterialTheme.typography.labelSmall)}
                }
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=2.dp,top=2.dp,bottom=2.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if(selected==null)"勾选一笔，仅删除该笔" else "选中第$selectedIndex 步 · ${selected.getString("label")}",
                    maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                Text(if(selected==null)"点击记录仍可回到历史步骤" else if(selected.getBoolean("canDelete"))
                    "保留其他构造，可撤销" else selected.getString("deleteReason"),
                    maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
            }
            IconButton(enabled=!busy&&selected?.getBoolean("canDelete")==true,onClick={
                val id=requireNotNull(selectedId)
                onDelete(documentId,id,revision)
                selectedId=null
            }) {Icon(Icons.Default.Delete,contentDescription="仅删除选中的一笔",
                tint=if(!busy&&selected?.getBoolean("canDelete")==true)MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f))}
        }
    }
    if(showBranches)AlertDialog(onDismissRequest={showBranches=false},
        title={Text("其他分支记录")},
        text={Column {
            Text("这些操作记录仍保留；此处只查看，不切换当前作品。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp).clipToBounds()) {
                val entries=snapshot.getJSONArray("otherBranches")
                items(entries.length(),key={entries.getJSONObject(it).getString("id")}) {index->
                    val step=entries.getJSONObject(index)
                    Column(Modifier.fillMaxWidth().clickable {
                        showBranches=false
                        detail=step
                    }.padding(vertical=8.dp)) {
                        Text(step.getString("label"))
                        Text(actor(step)+" · "+time(step),style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }},
        confirmButton={TextButton(onClick={showBranches=false}){Text("关闭")}})
    detail?.let {step->
        AlertDialog(onDismissRequest={detail=null},
            title={Text(step.getString("label"))},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(step.getString("category")+" · "+actor(step)+" · "+time(step))
                if(step.getString("summary").isNotBlank())Text(step.getString("summary"),Modifier.padding(top=12.dp))
            }},
            confirmButton={TextButton(onClick={detail=null}){Text("关闭")}})
    }
}
