package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.method.TextKeyListener
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.text.style.BackgroundColorSpan
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Drafts are resources, not history. All scene writes still go through ArtStore. */
internal class StudioSvgEditorModel(private val store:ArtStore) {
    var documentId by mutableStateOf("");private set
    var expectedRevision by mutableIntStateOf(0);private set
    var latestRevision by mutableIntStateOf(0);private set
    var scope by mutableStateOf("document");private set
    var objectIds by mutableStateOf<List<String>>(emptyList());private set
    var source by mutableStateOf("");private set
    var baseSource by mutableStateOf("");private set
    var selected by mutableStateOf<List<String>>(emptyList());private set
    var known by mutableStateOf<Set<String>>(emptySet());private set
    var ranges by mutableStateOf<List<ArtSvgCodeIndex.Range>>(emptyList());private set
    var message by mutableStateOf("")
    var ready by mutableStateOf(false);private set
    var stale by mutableStateOf(false);private set
    var editing by mutableStateOf(false)
    var picking by mutableStateOf(true)
    var loading by mutableIntStateOf(0);private set
    var working by mutableStateOf(false)
    var preview by mutableStateOf<Bitmap?>(null);private set
    val dirty get()=source!=baseSource
    fun markStale(){stale=true}
    private val writer=Executors.newSingleThreadScheduledExecutor()
    private var pending:ScheduledFuture<*>?=null
    private val main=Handler(Looper.getMainLooper())
    private fun draft()=JSONObject().put("documentId",documentId).put("expectedRevision",expectedRevision).put("scope",scope)
        .put("objectIds",JSONArray(objectIds)).put("source",source).put("baseSource",baseSource)
    fun save(immediate:Boolean=false) {
        if(!ready)return
        val captured=draft();pending?.cancel(false)
        val task=Runnable {try {store.saveSvgDraft(captured)} catch(e:Exception){main.post {message="草稿保存失败：${e.message}"}}}
        pending=null
        if(immediate)writer.execute(task)else pending=writer.schedule(task,250L,TimeUnit.MILLISECONDS)
    }
    fun close(){save(true);preview?.recycle();preview=null;writer.shutdown()}
    fun request(includeSource:Boolean=true)=JSONObject().put("documentId",documentId).put("expectedRevision",expectedRevision)
        .put("scope",scope).put("objectIds",JSONArray(objectIds)).apply {if(includeSource)put("source",source)}
    fun selectionRequest(ids:List<String>)=JSONObject().put("documentId",documentId).put("expectedRevision",latestRevision).put("objectIds",JSONArray(ids))
    fun edit(value:String) {
        require(value.toByteArray().size<=ArtSceneSvg.MAX_BYTES){"代码最多1MiB"}
        source=value;message="草稿尚未应用";clearPreview();save()
    }
    fun clearPreview(){preview?.recycle();preview=null}
    suspend fun index() {
        val captured=source
        val result=withContext(Dispatchers.IO){try {ArtSvgCodeIndex.ranges(captured)}catch(e:IllegalArgumentException){emptyList()}}
        if(source==captured)ranges=result
    }
    private fun accept(full:JSONObject) {
        source=full.getString("source");baseSource=source;expectedRevision=full.getInt("expectedRevision");latestRevision=expectedRevision
        known=full.getJSONArray("objects").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()}
        selected=ArtShapes.ids(full.getJSONArray("selectedObjectIds"));stale=false;ready=true;message="已读取${if(scope=="document")"全图" else "局部"}代码";clearPreview();save()
    }
    suspend fun bind(snapshot:JSONObject) {loading++;try {bindSnapshot(snapshot)}finally{loading--}}
    private suspend fun bindSnapshot(snapshot:JSONObject) {
        val id=snapshot.getString("id");val rev=snapshot.getInt("revision")
        if(documentId!=id||!ready) {
            if(documentId!=id){save(true);ready=false;documentId=id;scope="document";objectIds=emptyList();editing=false;clearPreview()}
            val saved=withContext(Dispatchers.IO){store.svgDraft(id)}
            if(saved!=null){scope=saved.getString("scope");objectIds=ArtShapes.ids(saved.getJSONArray("objectIds"))
                source=saved.getString("source");baseSource=saved.getString("baseSource");expectedRevision=saved.getInt("expectedRevision");ready=true}
        }
        latestRevision=rev;stale=dirty&&expectedRevision!=rev;selected=ArtSceneSvg.selected(snapshot.getJSONObject("state"))
        if(scope=="objects"&&!dirty&&selected.isNotEmpty()&&selected.any {it !in known})objectIds=selected
        // Export the supplied snapshot, so a simultaneous external write cannot mix revisions.
        val canonical=withContext(Dispatchers.IO){ArtSceneSvg.export(snapshot,scope,objectIds)}
        known=canonical.objects.let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()}
        if(!ready||!dirty){source=canonical.source;baseSource=source;expectedRevision=rev;ready=true;stale=false;message="已读取${if(scope=="document")"全图" else "局部"}代码";save()}
        else {stale=canonical.source!=baseSource;if(!stale){expectedRevision=rev;save()}else message="画布内容已变化；草稿保留。重新读取前可复制草稿。"}
        index()
    }
    suspend fun readScope(newScope:String,ids:List<String>,discard:Boolean=false) {
        require(!dirty||discard){"请先应用草稿，或明确重新读取后再切换范围"}
        val p=JSONObject().put("documentId",documentId).put("expectedRevision",latestRevision).put("scope",newScope).put("objectIds",JSONArray(ids))
        val full=withContext(Dispatchers.IO){store.svgDocument(p)};require(documentId==p.getString("documentId")&&latestRevision==p.getInt("expectedRevision")){"读取期间作品已变化"};scope=newScope;objectIds=ids;accept(full);index()
    }
    suspend fun reload() {
        val p=JSONObject().put("documentId",documentId).put("expectedRevision",latestRevision).put("scope",scope).put("objectIds",JSONArray(objectIds))
        val full=withContext(Dispatchers.IO){store.svgDocument(p)};require(documentId==p.getString("documentId")&&latestRevision==p.getInt("expectedRevision")){"读取期间作品已变化"};accept(full);index()
    }
    suspend fun check(previewImage:Boolean) {
        val p=request();val result=withContext(Dispatchers.IO){store.svgValidate(p)}
        require(documentId==p.getString("documentId")&&latestRevision==p.getInt("expectedRevision")&&source==p.getString("source")){"校验期间作品或草稿已变化"}
        if(!result.getBoolean("valid")){message="${result.optInt("line")}:${result.optInt("column")} ${result.getString("message")}";clearPreview();return}
        message="校验通过；尚未应用"
        if(previewImage) {
            val bitmap=withContext(Dispatchers.IO){val image=store.svgApply("AWEI",p,true).getJSONArray("mcp_content").getJSONObject(0)
                val bytes=android.util.Base64.decode(image.getString("data"),android.util.Base64.DEFAULT)
                requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)){"无法读取SVG预览"}}
            if(documentId!=p.getString("documentId")||latestRevision!=p.getInt("expectedRevision")||source!=p.getString("source")){bitmap.recycle();error("预览期间作品或草稿已变化")}
            clearPreview();preview=bitmap
        }
    }
    suspend fun applied(snapshot:JSONObject) {
        require(snapshot.getString("id")==documentId){"工程已切换；草稿保留"}
        latestRevision=snapshot.getInt("revision");reload();editing=false;message="SVG 已应用，可撤销"
    }
}

/** Selection highlights the complete source block without opening the keyboard. */
private class StudioSvgCodeView(context:Context):ScrollView(context) {
    val editor=EditText(context)
    private val horizontal=HorizontalScrollView(context)
    private var syncing=false
    private var highlights=emptyList<ArtSvgCodeIndex.Range>()
    var changed:(String)->Unit={}
    var picked:(Int)->Unit={}
    init {
        // AndroidView does not clip by default. Bound the native viewport itself so
        // scrolled source and selection highlights cannot paint over the canvas/toolbar.
        layoutParams=ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT)
        outlineProvider=ViewOutlineProvider.BOUNDS
        clipToOutline=true
        clipChildren=true
        clipToPadding=true
        isFillViewport=true
        isVerticalScrollBarEnabled=true
        isHorizontalScrollBarEnabled=false
        isScrollbarFadingEnabled=false
        scrollBarStyle=View.SCROLLBARS_INSIDE_INSET
        horizontal.apply {
            isFillViewport=true
            clipChildren=true
            clipToPadding=true
            isHorizontalScrollBarEnabled=true
            isVerticalScrollBarEnabled=false
            isScrollbarFadingEnabled=false
            scrollBarStyle=View.SCROLLBARS_INSIDE_INSET
        }
        editor.apply {
            typeface=Typeface.MONOSPACE;textSize=12f;setTextColor(android.graphics.Color.rgb(230,230,235));setBackgroundColor(android.graphics.Color.rgb(22,24,28))
            gravity=android.view.Gravity.TOP;setPadding(12,8,12,24);setHorizontallyScrolling(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!syncing)changed(s.toString())}
                override fun afterTextChanged(s:Editable?){}
            })
            filters=arrayOf(android.text.InputFilter {insert,start,end,old,dstart,dend ->
                val candidate=old.subSequence(0,dstart).toString()+insert.subSequence(start,end)+old.subSequence(dend,old.length)
                if(candidate.toByteArray().size>ArtSceneSvg.MAX_BYTES)old.subSequence(dstart,dend) else null
            })
            setOnClickListener {if(keyListener==null)picked(selectionStart.coerceAtLeast(0))}
        }
        horizontal.addView(editor,android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT))
        // Only this child grows with the code; the root stays at the allocated pane height.
        addView(horizontal,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
    }
    fun sync(source:String,editing:Boolean,selected:List<ArtSvgCodeIndex.Range>) {
        syncing=true
        try {
            if(editor.text.toString()!=source){val cursor=editor.selectionStart.coerceAtLeast(0).coerceAtMost(source.length);editor.setText(source);editor.setSelection(cursor)}
            if(editing&&editor.keyListener==null){editor.keyListener=TextKeyListener.getInstance();editor.showSoftInputOnFocus=true;editor.isCursorVisible=true}
            else if(!editing&&editor.keyListener!=null){editor.keyListener=null;editor.showSoftInputOnFocus=false;editor.isCursorVisible=false
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken,0)}
            val text=editor.text;text.getSpans(0,text.length,BackgroundColorSpan::class.java).forEach {text.removeSpan(it)}
            selected.filter {it.start>=0&&it.end<=text.length}.forEach {text.setSpan(BackgroundColorSpan(android.graphics.Color.rgb(46,76,105)),it.start,it.end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)}
            if(selected!=highlights&&(!editing||selected.map {it.id}!=highlights.map {it.id})&&selected.isNotEmpty())post {val layout=editor.layout ?: return@post
                val line=layout.getLineForOffset(selected.first().start.coerceAtMost(editor.text.length));smoothScrollTo(0,layout.getLineTop(line));horizontal.scrollTo(0,0)}
            highlights=selected
        }finally{syncing=false}
    }
}

@Composable internal fun StudioSvgEditor(model:StudioSvgEditorModel,busy:Boolean,onApply:(JSONObject)->Unit,onSelect:(JSONObject)->Unit) {
    val coroutine=rememberCoroutineScope();var reloadPrompt by remember {mutableStateOf(false)};var requestedScope by remember {mutableStateOf<String?>(null)}
    fun action(block:suspend ()->Unit){model.working=true;coroutine.launch {try {block()}catch(e:CancellationException){throw e}catch(e:Exception){model.message=e.message ?: "SVG操作失败"}finally{model.working=false}}}
    val enabled=model.ready&&!busy&&!model.working&&model.loading==0
    val canRead=model.documentId.isNotEmpty()&&!busy&&!model.working&&model.loading==0
    Column(Modifier.fillMaxSize().clipToBounds()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton({model.picking=!model.picking},enabled=!busy){Text(if(model.picking)"选对象" else "绘画")}
            TextButton({model.editing=!model.editing},enabled=enabled){Text(if(model.editing)"结束编辑" else "编辑")}
            TextButton({if(model.dirty){requestedScope="document";reloadPrompt=true}else action {model.readScope("document",emptyList())}},enabled=canRead){Text("全图")}
            TextButton({if(model.dirty){requestedScope="objects";reloadPrompt=true}else action {model.readScope("objects",model.selected)}},enabled=canRead&&model.selected.isNotEmpty()){Text("局部")}
            TextButton({action {model.check(false)}},enabled=enabled&&!model.stale){Text("校验")}
            TextButton({action {model.check(true)}},enabled=enabled&&!model.stale){Text("预览")}
            TextButton({onApply(model.request())},enabled=enabled&&model.dirty&&!model.stale){Text("应用")}
            TextButton({if(model.dirty){requestedScope=null;reloadPrompt=true}else action {model.reload()}},enabled=enabled){Text("重新读取")}
        }
        Text("${if(model.scope=="document")"全图" else "局部"} · ${if(model.dirty)"未应用草稿" else "已同步"} · ${model.message}",style=MaterialTheme.typography.labelSmall,
            modifier=Modifier.fillMaxWidth().padding(horizontal=6.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
        AndroidView(factory={StudioSvgCodeView(it)},modifier=Modifier.fillMaxWidth().weight(1f).clipToBounds(),update={view->
            view.changed={model.edit(it)}
            view.picked={offset->if(enabled&&!model.dirty&&!model.stale)ArtSvgCodeIndex.at(model.ranges.filter {it.id in model.known},offset)?.let {onSelect(model.selectionRequest(listOf(it.id)))}}
            view.sync(model.source,model.editing&&enabled,model.ranges.filter {it.id in model.selected})
        })
    }
    if(reloadPrompt)AlertDialog(onDismissRequest={reloadPrompt=false},title={Text("丢弃这份草稿并重新读取？")},text={Text("当前未应用的代码将被画布代码替换。")},
        confirmButton={TextButton({reloadPrompt=false;action {val next=requestedScope;if(next==null)model.reload()else model.readScope(next,if(next=="objects")model.selected else emptyList(),true)}}){Text("重新读取")}},dismissButton={TextButton({reloadPrompt=false}){Text("保留草稿")}})
    model.preview?.let {bitmap->AlertDialog(onDismissRequest={model.clearPreview()},title={Text("SVG预览 · 尚未应用")},text={Image(bitmap.asImageBitmap(),"SVG草稿预览",Modifier.fillMaxWidth())},
        confirmButton={TextButton({model.clearPreview();onApply(model.request())},enabled=enabled&&!model.stale){Text("应用")}},dismissButton={TextButton({model.clearPreview()}){Text("关闭预览")}})}
}
