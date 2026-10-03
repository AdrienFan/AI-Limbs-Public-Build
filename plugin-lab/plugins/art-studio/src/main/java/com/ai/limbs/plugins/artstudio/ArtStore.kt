package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Region
import android.graphics.Color
import java.io.ByteArrayOutputStream
import android.graphics.Matrix
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.nio.channels.FileLock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** Both the Host presentation and Resident business runtime use the same plugin data directory. */
internal class ArtStore(private val root: File) {
    private val drafts = File(root, "drafts")
    private val documents = File(root, "documents")
    private val assets = File(root, "assets")
    private val pointer = File(root, "current.txt")
    private val recentIndex = File(root, "recent.json")
    private val sessionIndex = File(root, "sessions.json")
    private val externalLinks = File(root, "external-links.json")
    private val editClipboard = File(root, "edit-clipboard.json")
    private val layerClipboard = File(root, "layer-clipboard.json")
    private val menuSettings = File(root, "menu-settings.json")
    private val menuUiRequest = File(root, "menu-ui-request.json")
    private val templates = File(root, "templates")
    private val backups = File(root, "backups")
    private val saveDirectories = ArtSaveDirectories(root)
    private val lockFile = File(root, "art-studio.lock")

    init {
        require(drafts.mkdirs() || drafts.isDirectory)
        require(documents.mkdirs() || documents.isDirectory)
        require(assets.mkdirs() || assets.isDirectory)
        require(templates.mkdirs() || templates.isDirectory)
        require(backups.mkdirs() || backups.isDirectory)
    }

    // The capability wrapper holds one lock through the edit and its rendered receipt.
    // Nested business methods on this same thread reuse it instead of acquiring an overlapping file lock.
    private val lockDepth = ThreadLocal.withInitial { 0 }
    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        if (lockDepth.get() > 0) block()
        else FileOutputStream(lockFile, true).channel.use { channel ->
            val lock: FileLock = channel.lock()
            lockDepth.set(1)
            try { block() } finally { lockDepth.remove(); lock.release() }
        }
    }

    /** Read the active document and render a view under one lock; no history or pointer mutation. */
    fun withViewSnapshot(block: (JSONObject?) -> JSONObject): JSONObject = locked {
        block(if (pointer.isFile) snapshot(loadCurrent()) else null)
    }

    fun withCanvasFeedback(block: () -> JSONObject): JSONObject = locked {
        fun referenceSignature(snapshot:JSONObject?):String {
            if(snapshot==null) return ""
            val state=snapshot.getJSONObject("state")
            if(ArtReferences.items(state).isEmpty()) return ""
            return JSONObject().put("references",JSONArray(ArtReferences.items(state)))
                .put("visible",state.optBoolean("referencesVisible",true)).toString()
        }
        fun assistantSignature(snapshot: JSONObject?): String {
            if(snapshot==null)return ""
            val state=snapshot.getJSONObject("state")
            if(ArtAssistants.items(state).isEmpty())return ""
            return JSONObject().put("assistants",JSONArray(ArtAssistants.items(state)))
                .put("selected",ArtAssistants.selected(state)).put("settings",ArtAssistants.settings(state)).toString()
        }
        val beforeSnapshot=if(pointer.isFile) snapshot(loadCurrent()) else null
        val before=referenceSignature(beforeSnapshot)
        val assistantBefore=assistantSignature(beforeSnapshot)
        val selectionBefore=beforeSnapshot?.getJSONObject("state")?.optJSONObject("selection")?.toString()
        val result = block()
        val after = if (pointer.isFile) snapshot(loadCurrent()) else null
        if(after!=null && referenceSignature(after)!=before) result.put("referenceFeedback",true)
        if(after!=null && assistantSignature(after)!=assistantBefore) result.put("assistantFeedback",true)
        if(after?.getJSONObject("state")?.optJSONObject("selection")?.toString()!=selectionBefore)result.put("selectionFeedback",true)
        try {
            ArtCanvasFeedback.attach(this, result, after)
        } catch (error: Exception) {
            // The edit has committed. Report a missing receipt explicitly and preserve its IDs;
            // throwing an ordinary tool failure here would encourage retrying an already-applied stroke.
            android.util.Log.e("ArtStudio", "Canvas changed but preview generation failed", error)
            result.put("thumbnail", JSONObject().put("status", "error").put("operationApplied", true)
                .put("documentId", after?.getString("id") ?: JSONObject.NULL)
                .put("revision", after?.getInt("revision") ?: JSONObject.NULL)
                .put("error", error.message ?: error.javaClass.simpleName))
        }
    }

    fun canvasRegion(x: Int, y: Int, width: Int, height: Int, maxEdge: Int,
        documentId: String? = null, expectedRevision: Int? = null, selectionOutline: Boolean = false): JSONObject = locked {
        val current = snapshot(loadCurrent())
        require(documentId == null || documentId == current.getString("id")) { "工程已经切换，请使用当前画布编号" }
        require(expectedRevision == null || expectedRevision == current.getInt("revision")) { "画布版本已经改变，请刷新后检查细节" }
        val image = ArtCanvasFeedback.preview(this, current,
            x, y, width, height, maxEdge, "region",selectionOutline=selectionOutline)
        JSONObject().put("regionPreview", image.getJSONObject("metadata"))
            .put("mcp_content", JSONArray().put(image.getJSONObject("content")))
    }

    fun create(width: Int, height: Int, background: String = "#FFFFFFFF",
               name: String = "未命名工程", actor: String = "AWEI"): JSONObject = locked {
        ArtImagePolicy.requireWorkingSize(width, height)
        requireColor(background)
        require(name.trim().isNotBlank()) { "工程名称不能为空" }
        val id = UUID.randomUUID().toString()
        val firstLayer = UUID.randomUUID().toString()
        val base = JSONObject().put("width", width).put("height", height).put("name", name.trim().take(100))
            .put("background", background).put("layers", JSONArray().put(newLayer(firstLayer, "paint", "绘画图层", "", "")))
            .put("selectedLayerId", firstLayer)
            .put("selection", JSONObject.NULL)
        val doc = JSONObject().put("format", 1).put("id", id).put("base", base)
            .put("createdBy", actor).put("operations", JSONArray())
        atomic(draft(id), doc.toString())
        atomic(pointer, id)
        markRecent(id)
        snapshot(doc)
    }

    fun current(): JSONObject = locked { snapshot(loadCurrent()) }

    private fun moveSettingsFile()=File(root,"move-settings.json")
    fun moveSettings():JSONObject=locked {
        val file=moveSettingsFile()
        if(file.isFile)ArtMove.validateSettings(JSONObject(file.readText())) else ArtMove.defaults()
    }
    fun configureMove(p:JSONObject):JSONObject=locked {
        val old=moveSettings();require(p.getLong("expectedSettingsRevision")==old.getLong("revision")) {"移动工具设置已更新，请重新读取"}
        val next=ArtMove.settings(old,p)
        if(next.toString()==old.toString())return@locked old
        require(old.getLong("revision")<Long.MAX_VALUE)
        next.put("revision",old.getLong("revision")+1);atomic(moveSettingsFile(),next.toString());next
    }
    private fun moveSnapshot(p:JSONObject,write:Boolean):JSONObject {
        val snap=current()
        if(write || p.has("documentId"))require(p.getString("documentId")==snap.getString("id")) {"工程已切换，请重新开始移动"}
        if(write || p.has("expectedRevision"))require(p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已更新，请重新开始移动"}
        return snap
    }
    fun moveHit(p:JSONObject):JSONObject=locked {
        val snap=moveSnapshot(p,false)
        val options=ArtMove.settings(moveSettings(),p)
        if(options.getString("layerMode")=="current")options.put("layerMode","content")
        ArtMove.hit(this,snap,p.getInt("x"),p.getInt("y"),options)
            .put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
    }
    fun moveNudge(actor:String,p:JSONObject):JSONObject=locked {
        val options=ArtMove.settings(moveSettings(),p)
        val delta=ArtMove.nudge(p.getString("direction"),p.optBoolean("large",false),options)
        move(actor,JSONObject(p.toString()).put("dx",delta.first).put("dy",delta.second).put("unit","px")
            .put("layerMode","current"))
    }
    fun move(actor:String,p:JSONObject):JSONObject=locked {
        require(actor in setOf("AWEI","LANER"))
        val snap=moveSnapshot(p,true);val state=snap.getJSONObject("state")
        val options=ArtMove.settings(moveSettings(),p)
        val pixels=ArtMove.selectionMode(state,options.getString("moveScope"))
        val id=if(pixels || options.getString("layerMode")=="current")p.optString("layerId",state.getString("selectedLayerId")) else {
            require(!p.has("layerId")) {"内容拾取模式不同时指定layerId"}
            val hit=ArtMove.hit(this,snap,p.getInt("pickX"),p.getInt("pickY"),options)
            require(hit.getBoolean("hit")) {"该位置没有命中可移动图层"};hit.getString("layerId")
        }
        val layer=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==id} ?: error("移动图层不存在")
        require(layer.getString("kind") in setOf("paint","image","text","vector","group","colorize"))
        require(ArtMove.visibility(state,layer)>0 && !ArtMenuOperations.isLocked(state,layer)) {"移动图层或父组已隐藏、透明或锁定"}
        if(pixels)require(layer.getString("kind") in setOf("paint","image")) {"选区像素搬移只支持绘画层和图像层；请显式切换整层模式，不自动栅格化"}
        val (dx,dy)=ArtMove.delta(p,options,pixels)
        val receipt=JSONObject().put("layerId",id).put("mode",if(pixels)"pixels" else "layer")
            .put("documentDx",dx).put("documentDy",dy).put("unit",p.optString("unit",options.getString("unit")))
        if(dx==0.0 && dy==0.0) {
            if(id==state.getString("selectedLayerId"))return@locked snap.put("movement",receipt.put("operationApplied",false))
            return@locked appendToCurrent(actor,"MOVE_LAYER",JSONObject().put("layerId",id).put("x",layer.getDouble("x")).put("y",layer.getDouble("y")))
                .put("movement",receipt.put("operationApplied",true).put("selectedLayerOnly",true))
        }
        if(!pixels) {
            val offset=floatArrayOf(dx.toFloat(),dy.toFloat())
            if(layer.optString("parentId").isNotBlank()) {
                val parent=ArtMenuOperations.layers(state).first {it.getString("id")==layer.getString("parentId")}
                val inverse=Matrix();require(ArtShapes.layerMatrix(state,parent).invert(inverse));inverse.mapVectors(offset)
            }
            val x=layer.getDouble("x")+offset[0];val y=layer.getDouble("y")+offset[1]
            require(x.isFinite() && y.isFinite() && kotlin.math.abs(x)<=1000000 && kotlin.math.abs(y)<=1000000)
            appendToCurrent(actor,"MOVE_LAYER",JSONObject().put("layerId",id).put("x",x).put("y",y))
                .put("movement",receipt.put("operationApplied",true))
        } else {
            val selection=state.getJSONObject("selection")
            val movedSelection=ArtMove.shiftedSelection(selection,dx,dy)
            val inverse=Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse)) {"图层变换不可逆"}
            val capture=ArtMovePixels.capture(this,snap,id,selection)
            val asset=UUID.randomUUID().toString()
            val params=JSONObject().put("layerId",id).put("asset",asset).put("sourceSelection",capture.mask)
                .put("selectionToLayer",ArtShapes.encode(inverse)).put("selection",movedSelection)
                .put("x",capture.bounds.left).put("y",capture.bounds.top).put("width",capture.bounds.width()).put("height",capture.bounds.height())
                .put("dx",dx.toInt()).put("dy",dy.toInt())
            ArtMovePixels.validate(params)
            atomicBytes(assetFile(asset),capture.bytes)
            val result=try {appendToCurrent(actor,"MOVE_PIXELS",params)}
                catch(error:Throwable) {assetFile(asset).delete();throw error}
            result.put("movement",receipt.put("operationApplied",true).put("visiblePixels",capture.visiblePixels).put("sourceStrokeRecordsRetained",true))
        }
    }

    fun measureSettings():JSONObject=locked {
        val file=File(root,"measure-settings.json")
        if(file.isFile)ArtMeasure.settings(JSONObject(file.readText()),JSONObject()) else ArtMeasure.defaults()
    }
    fun configureMeasure(p:JSONObject):JSONObject=locked {
        val old=measureSettings();require(p.getLong("expectedSettingsRevision")==old.getLong("revision")) {"测量设置已更新"}
        val next=ArtMeasure.settings(old,p)
        if(next.toString()==old.toString())return@locked old
        require(old.getLong("revision")<Long.MAX_VALUE);next.put("revision",old.getLong("revision")+1)
        atomic(File(root,"measure-settings.json"),next.toString());next
    }
    fun transformGeometry(p:JSONObject):JSONObject=locked {
        val snap=moveSnapshot(p,false);val state=snap.getJSONObject("state")
        val scope=p.getString("scope");require(scope in setOf("selection","layer"))
        val source=if(scope=="selection") {require(ArtMove.hasSelection(state));state.getJSONObject("selection")} else
            JSONObject(p.getJSONObject("sourceBounds").toString()).put("shape","rect")
        ArtTransform.geometry(ArtMove.bounds(source),p).put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
    }
    fun transformAffine(actor:String,p:JSONObject):JSONObject=locked {
        val snap=moveSnapshot(p,true);val state=snap.getJSONObject("state")
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(!ArtMenuOperations.isLocked(state,layer))
        val current=ArtShapes.layerMatrix(state,layer);val inverse=Matrix();require(current.invert(inverse))
        // A local correction conjugates a document-space delta without disturbing parent transforms or old x/y/scale/rotation APIs.
        val correction=Matrix(current).apply {postConcat(ArtTransform.affine(p));postConcat(inverse)}
        val result=layer.optJSONArray("affine")?.let {ArtShapes.matrix(it)} ?: Matrix()
        result.preConcat(correction)
        val encoded=ArtShapes.encode(result);ArtShapes.matrix(encoded)
        if(correction.isIdentity)return@locked snap.put("transformApplied",false)
        appendToCurrent(actor,"TRANSFORM_AFFINE",JSONObject().put("layerId",layer.getString("id")).put("affine",encoded)).put("transformApplied",true)
    }
    fun transformPixels(actor:String,p:JSONObject):JSONObject=locked {
        val snap=moveSnapshot(p,true);val state=snap.getJSONObject("state")
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(!ArtMenuOperations.isLocked(state,layer) && ArtMove.visibility(state,layer)>0)
        val scope=p.getString("scope");require(scope in setOf("selection","layer"))
        val selection=if(scope=="selection") {
            require(layer.getString("kind") in setOf("paint","image")) {"选区像素变换仅支持绘画层和图像层"}
            require(ArtMove.hasSelection(state));state.getJSONObject("selection")
        } else {
            require(p.getBoolean("bake")) {"整层像素变换须显式bake=true，确认栅格化和取样框外像素丢弃"}
            require(ArtMenuOperations.subtree(state,layer.getString("id")).none {it.getBoolean("locked")}) {"整层栅格化不能删除锁定的子图层"}
            JSONObject(p.getJSONObject("sourceBounds").toString()).put("shape","rect")
        }
        val plan=ArtTransform.plan(ArtMove.bounds(selection),p)
        if(plan.identity)return@locked snap.put("transformApplied",false)
        val inverse=Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse))
        val sourcePixels=ArtMove.bounds(selection).let {it.width().toLong()*it.height()}
        val outputPixels=plan.bounds.width().toLong()*plan.bounds.height()
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this,state,ArtMove.bounds(selection).width(),ArtMove.bounds(selection).height())+ArtBrush.renderOverhead(state,state.getInt("width"),state.getInt("height"))+sourcePixels*32+outputPixels*32,"像素变换事务")
        val capture=ArtMovePixels.capture(this,snap,layer.getString("id"),selection)
        val warped=ArtTransformPixels.render(capture,plan,p);val asset=UUID.randomUUID().toString()
        val event=JSONObject().put("layerId",layer.getString("id")).put("scope",scope).put("asset",asset)
            .put("sourceSelection",capture.mask).put("selection",warped.mask).put("selectionToLayer",ArtShapes.encode(inverse))
            .put("outputX",warped.bounds.left).put("outputY",warped.bounds.top).put("outputWidth",warped.bounds.width()).put("outputHeight",warped.bounds.height())
            .put("interpolation",p.optString("interpolation","bilinear")).put("mode",p.getString("mode"))
        ArtTransformPixels.validate(event);atomicBytes(assetFile(asset),warped.bytes)
        try {appendToCurrent(actor,"TRANSFORM_PIXELS",event).put("transformApplied",true).put("transformGeometry",ArtTransform.geometry(capture.bounds,p))}
        catch(error:Throwable) {assetFile(asset).delete();throw error}
    }

    private fun colorWorkspaceFile() = File(root, "color-workspace.json")
    fun colorState(): JSONObject = locked {
        val file = colorWorkspaceFile()
        if (file.isFile) {
            require(file.length() <= 1024 * 1024) { "颜色资源文件超限" }
            ArtColorWorkspace.validate(JSONObject(file.readText()))
        } else ArtColorWorkspace.defaults()
    }
    private fun saveColorState(next: JSONObject): JSONObject {
        val previous = colorState()
        val before = JSONObject(previous.toString()).apply { remove("revision") }
        val after = JSONObject(next.toString()).apply { remove("revision") }
        if (before.toString() == after.toString()) return previous
        require(previous.getLong("revision") < Long.MAX_VALUE)
        next.put("revision", previous.getLong("revision") + 1)
        atomic(colorWorkspaceFile(), ArtColorWorkspace.validate(next).toString())
        return next
    }
    fun setColor(p: JSONObject): JSONObject = locked {
        val target = ArtColorWorkspace.target(p.getString("target"))
        require(target != "none") { "设置颜色需指定 foreground/background" }
        val next = colorState().put(target, ArtColorWorkspace.color(p.getString("color")))
        saveColorState(next)
    }
    fun setColorEdits(foreground: String?, background: String?): JSONObject = locked {
        val next = colorState()
        if (foreground != null) next.put("foreground", ArtColorWorkspace.color(foreground))
        if (background != null) next.put("background", ArtColorWorkspace.color(background))
        saveColorState(next)
    }
    fun saveColorPalette(p: JSONObject): JSONObject = locked {
        saveColorState(ArtColorWorkspace.savePalette(colorState(), p))
    }
    fun deleteColorPalette(id: String): JSONObject = locked {
        saveColorState(ArtColorWorkspace.deletePalette(colorState(), id))
    }
    /** Sampling and resource updates share the file lock across Host/Resident; no document write. */
    fun sampleColor(p: JSONObject, pick: Boolean = false): JSONObject = locked {
        val snap = current()
        if (p.has("documentId")) require(p.getString("documentId") == snap.getString("id")) { "工程已切换，请重新取色" }
        if (p.has("expectedRevision")) require(p.getInt("expectedRevision") == snap.getInt("revision")) { "工程已更新，请重新取色" }
        val merged = p.optBoolean("sampleMerged", true)
        val layerId = if (merged) null else p.optString("layerId", snap.getJSONObject("state").getString("selectedLayerId"))
        val x = p.getInt("x"); val y = p.getInt("y"); val radius = p.optInt("radius", 0)
        val blend = p.optInt("blend", 100)
        require(radius in 0..32 && blend in 0..100)
        val workspace = if (pick) colorState() else null
        val target = if (pick) ArtColorWorkspace.target(p.optString("target", "foreground")) else "none"
        val paletteId = if (pick && p.has("paletteId")) p.getString("paletteId") else null
        // Reject invalid destinations before allocating or updating any resource.
        if (paletteId != null) ArtColorWorkspace.palette(workspace!!, paletteId)
        val base = if (blend == 100) null else if (p.has("baseColor")) ArtColorWorkspace.color(p.getString("baseColor"))
            else {
                require(pick && target != "none") { "blend<100须传baseColor，或使用color.pick并指定前景/背景目标" }
                workspace!!.getString(target)
            }
        val bitmap = ArtColorSampler.renderSource(this, snap, layerId)
        val sampled = try { ArtColorSampler.sample(bitmap, x, y, radius) } finally { bitmap.recycle() }
        val value = if (base == null) sampled else ArtColorSampler.blend(Color.parseColor(base), sampled, blend)
        require(Color.alpha(value) > 0) { "透明区域没有可取的颜色" }
        val color = String.format(java.util.Locale.ROOT, "#%08X", value)
        val result = JSONObject().put("color", color).put("rawColor", String.format(java.util.Locale.ROOT, "#%08X", sampled))
            .put("x", x).put("y", y).put("radius", radius).put("blend", blend).put("sampleMerged", merged)
            .put("documentId", snap.getString("id")).put("revision", snap.getInt("revision"))
        if (layerId != null) result.put("layerId", layerId)
        if (pick) {
            val (next, added) = ArtColorWorkspace.picked(workspace!!, color, target, paletteId)
            result.put("target", target).put("paletteAdded", added).put("colorState", saveColorState(next))
            if (paletteId != null) result.put("paletteId", paletteId)
        }
        result
    }

    fun shapes(p: JSONObject, hit: Boolean = false, box: Boolean = false): JSONObject = locked {
        val snapshot = snapshot(loadCurrent())
        require(p.getString("documentId") == snapshot.getString("id")) { "工程已切换" }
        val state = snapshot.getJSONObject("state")
        val layerId = p.getString("layerId")
        val layer = ArtShapes.layer(state, layerId)
        val result = ArtShapes.describe(state, layerId)
            .put("documentId", snapshot.getString("id")).put("revision", snapshot.getInt("revision"))
        if (box) {
            require(ArtShapes.visible(state, layer)) { "矢量层或父组不可见" }
            val x=p.getDouble("x");val y=p.getDouble("y")
            val w=p.getDouble("width");val h=p.getDouble("height")
            require(listOf(x,y,w,h,x+w,y+h).all { it.isFinite() && kotlin.math.abs(it)<=1000000.0 } && w>0 && h>0)
            val inverse=Matrix();check(ArtShapes.layerMatrix(state,layer).invert(inverse))
            val clip=android.graphics.Path().apply {
                addRect(x.toFloat(),y.toFloat(),(x+w).toFloat(),(y+h).toFloat(),android.graphics.Path.Direction.CW)
                transform(inverse)
            }
            result.put("boxedIds",JSONArray(ArtShapes.box(layer,clip,p.optBoolean("contained",true))))
        }
        if (hit) {
            require(ArtShapes.visible(state, layer)) { "矢量层或父组不可见" }
            val point = floatArrayOf(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
            require(point.all { it.isFinite() && kotlin.math.abs(it) <= 1000000f })
            val inverse = Matrix()
            check(ArtShapes.layerMatrix(state, layer).invert(inverse))
            inverse.mapPoints(point)
            val tolerance = p.optDouble("tolerance", 0.0).toFloat()
            require(tolerance.isFinite() && tolerance in 0f..1024f)
            val vector = floatArrayOf(tolerance, 0f); inverse.mapVectors(vector)
            val localTolerance = kotlin.math.hypot(vector[0], vector[1])
            require(localTolerance.isFinite() && localTolerance in 0f..1000000f) { "当前图层比例下命中容差过大" }
            result.put("hitId", ArtShapes.hit(layer, point[0], point[1], localTolerance) ?: JSONObject.NULL)
        }
        result
    }

    fun saveDirectorySettings(): JSONObject = locked { saveDirectories.describe() }

    fun setSaveDirectory(directory: String): JSONObject = locked { saveDirectories.setDirectory(directory) }

    fun exportDirectory(): File = saveDirectories.outputDirectory("exports")

    fun menuContext(): JSONObject = locked {
        JSONObject().put("document", if (pointer.isFile) snapshot(loadCurrent()) else JSONObject.NULL)
            .put("layerClipboard", layerClipboard.isFile).put("moveSettings",moveSettings()).put("measureSettings",measureSettings())
            .put("settings", readMenuSettings()).put("dockPanels", readDockPanels()).put("storage", saveDirectories.describe())
    }

    fun menuUiState(): JSONObject = locked {
        JSONObject().put("settings", readMenuSettings()).put("dockPanels", readDockPanels()).put("storage", saveDirectories.describe())
            .put("moveSettings",moveSettings()).put("measureSettings",measureSettings()).put("layerClipboard",layerClipboard.isFile)
            .put("request", if (menuUiRequest.isFile) JSONObject(menuUiRequest.readText()) else JSONObject.NULL)
    }

    fun ackMenuUiRequest(id: String): JSONObject = locked {
        val request=JSONObject(menuUiRequest.readText())
        if(request.getString("id")==id) {
            request.put("applied",true)
            atomic(menuUiRequest,request.toString())
        }
        JSONObject().put("requestId",id).put("applied",request.getString("id")==id)
    }

    private val dockPanelsFile = File(root, "dock-panels.json")

    private fun readDockPanels(): JSONObject =
        if (dockPanelsFile.isFile) JSONObject(dockPanelsFile.readText()) else ArtDockPanels.initial()

    fun dockPanelState(): JSONObject = locked { readDockPanels() }

    fun changeDockPanels(command: String, panel: String? = null, enabled: Boolean? = null,
        suppressCloseConfirmation: Boolean = false): JSONObject = locked {
        val current = readDockPanels()
        val next = ArtDockPanels.change(current, command, panel, enabled, suppressCloseConfirmation)
        atomic(dockPanelsFile, next.toString())
        val opened = when (command) {
            "set_visible" -> panel?.takeIf { enabled == true }
            "expand" -> panel
            "restore" -> next.optString("activePane").takeIf { current.getBoolean("allCollapsed") && it in ArtDockPanels.ids }
            else -> null
        }
        if (opened != null) {
            val settings = readMenuSettings().put("panelsHidden", false)
            atomic(menuSettings, settings.toString())
            atomic(menuUiRequest, JSONObject().put("id", UUID.randomUUID().toString())
                .put("action", "docker." + opened).toString())
        }
        next
    }

    private fun readMenuSettings(): JSONObject =
        if (menuSettings.isFile) JSONObject(menuSettings.readText()) else JSONObject()
            .put("brushWidth", 6.0).put("brushOpacity", 1.0).put("selectionVisible", true)
            .put("panelsHidden", false).put("gridVisible", false).put("pixelGridVisible", true)

    /** The same dispatcher is called by the popup menus and Laner's registered capability. */
    fun executeMenu(actor: String, action: String, arguments: JSONObject): JSONObject = locked {
        require(actor in setOf("AWEI", "LANER"))
        val context = JSONObject().put("document", if (pointer.isFile) snapshot(loadCurrent()) else JSONObject.NULL)
            .put("layerClipboard", layerClipboard.isFile).put("settings", readMenuSettings())
            .put("storage", saveDirectories.describe())
        val item = ArtStudioMenuCatalog.find(action) ?: error("未知菜单操作：$action")
        val availability = ArtStudioMenuCatalog.availability(item, context)
        require(availability.first) { availability.second }
        val p = JSONObject(arguments.toString())
        var fields=item.getJSONArray("parameters")
        if(action=="filter_apply_reprompt") {
            context.getJSONObject("document").getJSONObject("state").getJSONObject("lastFilter").let { previous ->
                fields=ArtStudioMenuCatalog.find(previous.getString("action"))!!.getJSONArray("parameters")
                for(n in 0 until fields.length()) {
                    val field=fields.getJSONObject(n);val key=field.getString("name")
                    if(previous.getJSONObject("parameters").has(key)) field.put("default",previous.getJSONObject("parameters").get(key))
                }
            }
        }
        if (action == "options_configure") {
            for (n in 0 until fields.length()) {
                val field = fields.getJSONObject(n)
                if (field.getString("name") == "confirmPanelClose")
                    field.put("default", readDockPanels().getBoolean("confirmClose"))
            }
        }
        val allowed=mutableSetOf("documentId","expectedRevision")
        if (action in setOf("import_layer_from_file", "import_layer_as_paint_layer")) {
            allowed.add("confirmResize")
            if (p.has("confirmResize")) require(p.get("confirmResize") is JSONObject) { "缩小确认必须是 imagePlan.confirmation 对象" }
        }
        for(n in 0 until fields.length()) {
            val field=fields.getJSONObject(n);val key=field.getString("name");allowed.add(key)
            if(!p.has(key)) {
                require(field.has("default")) { "缺少菜单参数：$key" }
                p.put(key,field.get("default"))
            }
            val value=p.get(key)
            require(when(field.getString("type")) {
                "string" -> value is String
                "boolean" -> value is Boolean
                "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble()==value.toLong().toDouble()
                "number" -> value is Number && value.toDouble().isFinite()
                else -> false
            }) { "菜单参数类型无效：$key" }
            field.optJSONArray("choices")?.let { choices ->
                require((0 until choices.length()).any { choices.get(it)==value }) { "菜单参数选项无效：$key" }
            }
        }
        require(p.keys().asSequence().all { it in allowed }) { "菜单包含未知参数" }
        if (item.optBoolean("documentWrite")) {
            val doc = context.getJSONObject("document")
            require(p.getString("documentId") == doc.getString("id") &&
                p.getInt("expectedRevision") == doc.getInt("revision")) {
                "工程或修订已经被另一端修改，请刷新后重试"
            }
        }
        val snap = context.optJSONObject("document")
        val state = snap?.getJSONObject("state")
        val active = state?.let { ArtMenuOperations.active(it) }
        fun edit(type: String, params: JSONObject): JSONObject = appendToCurrent(actor, type, params)
        fun change(remove: List<String>, insert: List<JSONObject>, index: Int, selected: String,
                   label: String = item.getString("title"), background: String? = null,
                   filter: JSONObject? = null): JSONObject {
            val params = ArtMenuOperations.change(requireNotNull(state), remove, insert, index, selected, label)
            if (background != null) params.put("background", background)
            if (filter != null) params.put("filter", filter)
            return edit("MENU_LAYER_CHANGE", params)
        }
        fun raster(bitmap: Bitmap, id: String, name: String): JSONObject {
            val asset = UUID.randomUUID().toString()
            val bytes = try { ArtImagePolicy.encodePng(bitmap, MAX_ASSET_BYTES) } finally { bitmap.recycle() }
            atomicBytes(assetFile(asset), bytes)
            val result=ArtMenuOperations.rasterLayer(id,name,asset)
            // Replacing an existing layer retains its explicitly stored label. New/merged layers remain unlabeled.
            state?.let {s->ArtMenuOperations.layers(s).firstOrNull {it.getString("id")==id}?.let {original->
                if(original.has("colorLabel"))result.put("colorLabel",ArtLayerLabels.value(original))
            }}
            return result
        }
        when (action) {
            "add_new_colorize_mask" -> edit("COLORIZE_CREATE",
                JSONObject().put("id",UUID.randomUUID().toString()).put("sourceLayerId",requireNotNull(active).getString("id")))
            "add_new_shape_layer" -> edit("VECTOR_LAYER_CREATE",
                JSONObject().put("id", UUID.randomUUID().toString()).put("name", p.getString("name"))
                    .put("parentId", active?.let { if (it.getString("kind") == "group") it.getString("id") else it.optString("parentId") } ?: "")
                    .put("select", true))
            "add_new_paint_layer", "add_new_group_layer" -> edit(
                if (action == "add_new_group_layer") "GROUP_CREATE" else "LAYER_CREATE",
                JSONObject().put("id", UUID.randomUUID().toString()).put("name", p.getString("name").trim()
                    .also { require(it.isNotBlank() && it.length <= 100) { "名称需要 1–100 个字符" } })
                    .put("parentId", active?.let { if (it.getString("kind") == "group") it.getString("id") else it.optString("parentId") } ?: "")
                    .put("select", true))
            "duplicatelayer" -> edit("LAYER_COPY", JSONObject().put("id", requireNotNull(active).getString("id"))
                .put("newId", UUID.randomUUID().toString()).put("select", true))
            "copy_layer_clipboard", "cut_layer_clipboard" -> {
                val selected = requireNotNull(active)
                val tree = ArtMenuOperations.subtree(requireNotNull(state), selected.getString("id"))
                require(selected.optString("parentId").isBlank()) { "结构化图层剪贴板当前仅支持根图层或根图层组" }
                if (action == "cut_layer_clipboard") require(tree.none { ArtMenuOperations.isLocked(state, it) }) { "图层或组已锁定" }
                val clip = JSONObject().put("layers", JSONArray(tree)).put("rootId", selected.getString("id"))
                    .put("sourceDocument", requireNotNull(snap).getString("id")).put("sourceActor", actor)
                // Clipboard content is durable before a cut can remove the source tree.
                atomic(layerClipboard, clip.toString())
                if (action == "cut_layer_clipboard") {
                    val ids = tree.map { it.getString("id") }
                    val remaining = ArtMenuOperations.layers(state).filterNot { it.getString("id") in ids }
                    change(ids, emptyList(), 0, remaining.lastOrNull()?.getString("id") ?: "")
                } else JSONObject().put("copied", true).put("layerCount", tree.size)
            }
            "paste_layer_from_clipboard" -> {
                val clip = JSONObject(layerClipboard.readText())
                val originals = clip.getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val copies = ArtMenuOperations.cloneTree(originals)
                val rootIndex = originals.indexOfFirst { it.getString("id") == clip.getString("rootId") }
                require(rootIndex >= 0)
                change(emptyList(), copies, ArtMenuOperations.layers(requireNotNull(state)).size, copies[rootIndex].getString("id"))
            }
            "create_quick_group" -> {
                val selected = requireNotNull(active); val all = ArtMenuOperations.layers(requireNotNull(state))
                val id = UUID.randomUUID().toString()
                val group = newLayer(id, "group", p.getString("name").trim().also {
                    require(it.isNotBlank() && it.length<=100) { "名称需要 1–100 个字符" }
                }, selected.optString("parentId"), "")
                val child = JSONObject(selected.toString()).put("parentId", id)
                change(listOf(selected.getString("id")), listOf(group, child), all.indexOf(selected), id)
            }
            "quick_ungroup" -> {
                val group = requireNotNull(active); val all = ArtMenuOperations.layers(requireNotNull(state))
                val children = all.filter { it.optString("parentId") == group.getString("id") }
                val remove = listOf(group.getString("id")) + children.map { it.getString("id") }
                val remainingBefore = all.take(all.indexOf(group)).count { it.getString("id") !in remove }
                val moved = children.map { JSONObject(it.toString()).put("parentId", group.optString("parentId")) }
                change(remove, moved, remainingBefore, moved.lastOrNull()?.getString("id") ?: group.optString("parentId"))
            }
            "new_from_visible" -> {
                val view = JSONObject(requireNotNull(snap).toString())
                view.getJSONObject("state").put("background", "#00000000")
                val layer = raster(ArtRenderer.render(this, view), UUID.randomUUID().toString(), "可见图层合成")
                change(emptyList(), listOf(layer), ArtMenuOperations.layers(requireNotNull(state)).size, layer.getString("id"))
            }
            "merge_layer" -> {
                val pair = ArtMenuOperations.mergePair(requireNotNull(state))
                val trees = pair.flatMap { ArtMenuOperations.subtree(state, it.getString("id")) }
                val ids = trees.map { it.getString("id") }
                val layer = raster(ArtRenderer.render(this, ArtMenuOperations.isolated(requireNotNull(snap), ids.toSet())),
                    pair[0].getString("id"), pair[0].getString("name"))
                val all = ArtMenuOperations.layers(state)
                val index = all.take(all.indexOf(pair[0])).count { it.getString("id") !in ids }
                change(ids, listOf(layer), index, layer.getString("id"))
            }
            "flatten_layer", "convert_to_paint_layer" -> {
                val selected = requireNotNull(active); val tree = ArtMenuOperations.subtree(requireNotNull(state), selected.getString("id"))
                val ids = tree.map { it.getString("id") }
                val layer = raster(ArtRenderer.render(this, ArtMenuOperations.isolated(requireNotNull(snap), ids.toSet(), selected.getString("id"))),
                    selected.getString("id"), selected.getString("name"))
                    .put("visible", selected.getBoolean("visible")).put("opacity", selected.getDouble("opacity"))
                    .put("blend", selected.getString("blend"))
                val all = ArtMenuOperations.layers(state)
                change(ids, listOf(layer), all.take(all.indexOf(selected)).count { it.getString("id") !in ids }, layer.getString("id"))
            }
            "flatten_image" -> {
                val layer = raster(ArtRenderer.render(this, requireNotNull(snap)), UUID.randomUUID().toString(), "合并画布")
                change(ArtMenuOperations.layers(requireNotNull(state)).map { it.getString("id") }, listOf(layer), 0,
                    layer.getString("id"), background = "#00000000")
            }
            "import_layer_from_file", "import_layer_as_paint_layer" -> {
                val bytes = imageBytes(p.getString("base64"))
                val currentState = requireNotNull(state)
                val (bitmap, metadata) = ArtImagePolicy.decode(bytes, p.optJSONObject("confirmResize"),
                    ArtImagePolicy.renderBytes(this, currentState, currentState.getInt("width"), currentState.getInt("height")))
                val layer = raster(bitmap, UUID.randomUUID().toString(), "导入绘画图层")
                try {
                    change(emptyList(),listOf(layer),ArtMenuOperations.layers(currentState).size,layer.getString("id"))
                        .put("imageImport", metadata)
                } catch (error: Throwable) {
                    // A failed insertion must not leave the newly encoded image orphaned.
                    ArtMenuOperations.assets(layer) { node, key -> assetFile(node.getString(key)).delete() }
                    throw error
                }
            }
            "save_node_as_image" -> {
                val selected = requireNotNull(active)
                val tree = ArtMenuOperations.subtree(requireNotNull(state), selected.getString("id"))
                ArtRenderer.export(this, ArtMenuOperations.isolated(requireNotNull(snap),
                    tree.map { it.getString("id") }.toSet(), selected.getString("id")), "png", p.optString("name", "layer"))
            }
            "save_groups_as_images" -> {
                val images=JSONArray()
                ArtMenuOperations.layers(requireNotNull(state)).filter {
                    it.getString("kind")=="group" && it.optString("parentId").isBlank()
                }.forEachIndexed { index,group ->
                    val ids=ArtMenuOperations.subtree(state,group.getString("id")).map { it.getString("id") }.toSet()
                    images.put(ArtRenderer.export(this,ArtMenuOperations.isolated(requireNotNull(snap),ids,group.getString("id")),
                        "png","group-${index+1}").put("layerName",group.getString("name")))
                }
                JSONObject().put("images",images)
            }
            "cut_selection_to_new_layer", "copy_selection_to_new_layer" -> {
                val selected=requireNotNull(active); val currentState=requireNotNull(state)
                val area=editingRectangle(currentState)
                val clip=copyPixelsUnlocked(actor)
                val layer=ArtMenuOperations.rasterLayer(UUID.randomUUID().toString(),"选区图层",clip.getString("asset"))
                layer.getJSONArray("contentOrder").getJSONObject(0).put("x",area[0]).put("y",area[1])
                val all=ArtMenuOperations.layers(currentState)
                if(action=="cut_selection_to_new_layer") {
                    val edited=JSONObject(selected.toString())
                    contentOrder(edited).put(JSONObject().put("kind","clear").put("x",area[0]).put("y",area[1])
                        .put("width",area[2]).put("height",area[3]).put("selection",JSONObject(currentState.getJSONObject("selection").toString())))
                    change(listOf(selected.getString("id")),listOf(edited,layer),all.indexOf(selected),layer.getString("id"))
                } else change(emptyList(),listOf(layer),all.indexOf(selected)+1,layer.getString("id"))
            }
            "mirrorNodeX", "mirrorNodeY", "mirrorAllNodesX", "mirrorAllNodesY" -> {
                val all=ArtMenuOperations.layers(requireNotNull(state))
                val targets=if(action.startsWith("mirrorAll")) all else listOf(requireNotNull(active))
                val replacements=targets.associate { selected ->
                    val id=selected.getString("id")
                    val input=ArtRenderer.render(this,ArtMenuOperations.isolated(requireNotNull(snap),setOf(id),id))
                    val output=Bitmap.createBitmap(input.width,input.height,Bitmap.Config.ARGB_8888)
                    try {
                        val canvas=Canvas(output)
                        if(action.endsWith("X")) { canvas.translate(input.width.toFloat(),0f);canvas.scale(-1f,1f) }
                        else { canvas.translate(0f,input.height.toFloat());canvas.scale(1f,-1f) }
                        canvas.drawBitmap(input,0f,0f,Paint())
                    } finally { input.recycle() }
                    id to raster(output,id,selected.getString("name")).put("opacity",selected.getDouble("opacity"))
                        .put("blend",selected.getString("blend"))
                }
                change(all.map { it.getString("id") },all.map { replacements[it.getString("id")] ?: JSONObject(it.toString()) },
                    0,state.getString("selectedLayerId"))
            }
            "rotateAllLayers", "rotateAllLayersCW90", "rotateAllLayersCCW90", "rotateAllLayers180" -> {
                val degrees=when(action) { "rotateAllLayersCW90" -> 90.0; "rotateAllLayersCCW90" -> -90.0
                    "rotateAllLayers180" -> 180.0; else -> p.getDouble("degrees") }
                require(degrees.isFinite())
                val currentState=requireNotNull(state); val cx=currentState.getDouble("width")/2;val cy=currentState.getDouble("height")/2
                val radians=Math.toRadians(degrees)
                val all=ArtMenuOperations.layers(currentState)
                val rotated=all.map { old -> JSONObject(old.toString()).apply {
                    if(old.optString("parentId").isBlank()) {
                        val dx=old.getDouble("x")-cx;val dy=old.getDouble("y")-cy
                        put("x",cx+dx*cos(radians)-dy*sin(radians)).put("y",cy+dx*sin(radians)+dy*cos(radians))
                            .put("rotation",old.getDouble("rotation")+degrees)
                    }
                } }
                change(all.map { it.getString("id") },rotated,0,currentState.getString("selectedLayerId"))
            }
            "histogram" -> {
                val selected = requireNotNull(active)
                val tree = ArtMenuOperations.subtree(requireNotNull(state), selected.getString("id"))
                val bitmap = ArtRenderer.render(this, ArtMenuOperations.isolated(requireNotNull(snap), tree.map { it.getString("id") }.toSet(), selected.getString("id")))
                try { ArtMenuOperations.histogram(bitmap) } finally { bitmap.recycle() }
            }
            "rotatelayer", "rotateLayerCW90", "rotateLayerCCW90", "rotateLayer180", "offsetlayer" -> {
                val selected = requireNotNull(active)
                val params = JSONObject().put("id", selected.getString("id"))
                if (action == "offsetlayer") {
                    params.put("x",selected.getDouble("x")+p.getDouble("dx")).put("y",selected.getDouble("y")+p.getDouble("dy"))
                } else {
                    val degrees = when (action) { "rotateLayerCW90" -> 90.0; "rotateLayerCCW90" -> -90.0
                        "rotateLayer180" -> 180.0; else -> p.getDouble("degrees") }
                    require(degrees.isFinite())
                    // Rotate around canvas center in parent coordinates; keep the structured strokes editable.
                    val cx = requireNotNull(state).getDouble("width")/2; val cy = state.getDouble("height")/2
                    val radians = Math.toRadians(degrees)
                    val dx = selected.getDouble("x")-cx; val dy=selected.getDouble("y")-cy
                    params.put("rotation", selected.getDouble("rotation")+degrees)
                        .put("x",cx+dx*cos(radians)-dy*sin(radians)).put("y",cy+dx*sin(radians)+dy*cos(radians))
                }
                edit("TRANSFORM",params)
            }
            "select_all" -> edit("SELECTION_CREATE", JSONObject().put("x",0).put("y",0)
                .put("width",requireNotNull(state).getInt("width")).put("height",state.getInt("height")))
            "deselect" -> edit("SELECTION_CLEAR",JSONObject())
            "reselect" -> edit("SELECTION_CREATE",JSONObject(requireNotNull(state).getJSONObject("previousSelection").toString()))
            "selectionscale", "edit_selection", "growselection", "shrinkselection" -> {
                val selection = JSONObject(requireNotNull(state).getJSONObject("selection").toString())
                if (action in setOf("growselection","shrinkselection")) {
                    val amount = p.getDouble("pixels") * if (action == "shrinkselection") -1 else 1
                    require(p.getDouble("pixels") >= 0 && amount.isFinite())
                    val processed=ArtSoftSelection.process(selection,JSONObject().put("expand",amount),requireNotNull(state).getInt("width"),state.getInt("height"))
                    return@locked edit("SELECTION_CREATE",processed).put("selectionFeedback",true)
                } else {
                    selection.put("width",p.getDouble("width")).put("height",p.getDouble("height"))
                    if (action == "edit_selection") selection.put("x",p.getDouble("x")).put("y",p.getDouble("y"))
                }
                require(selection.getDouble("width") > 0 && selection.getDouble("height") > 0) { "选区尺寸必须大于零" }
                edit("SELECTION_CREATE",selection)
            }
            "filter_apply_again", "filter_apply_reprompt" -> {
                val previous = requireNotNull(state).getJSONObject("lastFilter")
                val parameters=JSONObject(previous.getJSONObject("parameters").toString())
                if(action=="filter_apply_reprompt") {
                    ArtStudioMenuCatalog.find(previous.getString("action"))!!.getJSONArray("parameters").let { fields ->
                        for(n in 0 until fields.length()) {
                            val key=fields.getJSONObject(n).getString("name")
                            if(p.has(key)) parameters.put(key,p.get(key))
                        }
                    }
                }
                applyMenuFilter(actor, previous.getString("action"), parameters, snap,
                    item.getString("title"), ::raster, ::change)
            }
            "filter.invert", "filter.desaturate", "filter.threshold", "filter.posterize", "filter.maximize",
            "filter.minimize", "filter.resettransparent" ->
                applyMenuFilter(actor,action,p,requireNotNull(snap),item.getString("title"),::raster,::change)
            "art.storage_directory" -> saveDirectories.setDirectory(p.getString("directory"))
            "options_configure", "reset_configurations", "toggle_display_selection", "view_toggledockers" -> {
                val settings = readMenuSettings()
                when (action) {
                    "options_configure" -> {
                        val brushWidth=p.getDouble("brushWidth"); val brushOpacity=p.getDouble("brushOpacity")
                        require(brushWidth.isFinite() && brushWidth in 0.1..512.0 && brushOpacity in 0.0..1.0)
                        settings.put("brushWidth",brushWidth).put("brushOpacity",brushOpacity)
                        changeDockPanels("set_confirmation", enabled = p.getBoolean("confirmPanelClose"))
                    }
                    "reset_configurations" -> {
                        settings.put("brushWidth",6.0).put("brushOpacity",1.0)
                            .put("selectionVisible",true).put("panelsHidden",false).put("gridVisible",false).put("pixelGridVisible",true)
                        changeDockPanels("set_confirmation", enabled = true)
                    }
                    "toggle_display_selection" -> settings.put("selectionVisible",p.getBoolean("enabled"))
                    "view_toggledockers" -> settings.put("panelsHidden",p.getBoolean("enabled"))
                }
                atomic(menuSettings,settings.toString())
                settings
            }
            "docker.color", "docker.layers", "docker.brushes", "docker.footprints" -> {
                val state = changeDockPanels("set_visible", action.removePrefix("docker."), p.getBoolean("enabled"))
                JSONObject().put("accepted", true).put("dockPanels", state).apply {
                    if (p.getBoolean("enabled")) put("requestId", JSONObject(menuUiRequest.readText()).getString("id"))
                }
            }
            "window.current" -> requireNotNull(snap)
            "help_contents", "help_whats_this", "help_show_tip", "buginfo", "sysinfo", "help_about_app" ->
                ArtStudioMenuCatalog.help(action,context)
            else -> error("菜单尚未实现：$action")
        }
    }

    private fun applyMenuFilter(actor: String, action: String, p: JSONObject, snapshot: JSONObject?, label: String,
        raster: (Bitmap,String,String) -> JSONObject,
        change: (List<String>,List<JSONObject>,Int,String,String,String?,JSONObject?) -> JSONObject): JSONObject {
        require(actor in setOf("AWEI","LANER"))
        val snap=requireNotNull(snapshot); val state=snap.getJSONObject("state")
        require(ArtMenuOperations.pixelsEditable(state)) { "滤镜需要可见、未锁定且未经变换的根像素图层" }
        val selected=requireNotNull(ArtMenuOperations.active(state)); val id=selected.getString("id")
        val bitmap=ArtRenderer.render(this,ArtMenuOperations.isolated(snap,setOf(id),id))
        val layer = try {
            ArtMenuOperations.filter(bitmap,action,p,state.optJSONObject("selection"))
            raster(bitmap,id,selected.getString("name"))
        } catch (error: Throwable) { if (!bitmap.isRecycled) bitmap.recycle(); throw error }
        layer.put("opacity",selected.getDouble("opacity")).put("blend",selected.getString("blend"))
        val clean = JSONObject(p.toString()).apply { remove("expectedRevision"); remove("documentId") }
        return change(listOf(id),listOf(layer),ArtMenuOperations.layers(state).indexOf(selected),id,label,null,
            JSONObject().put("action",action).put("parameters",clean))
    }

    fun revision(): String = locked {
        if (!pointer.isFile) ""
        else {
            val id = pointer.readText().trim()
            validateId(id)
            val marker = File(documents, id + ".sha256")
            "$id:${draft(id).lastModified()}:${draft(id).length()}:" +
                "${marker.lastModified()}:${marker.length()}:" +
                "${archive(id).lastModified()}:${externalLink(id)?.optBoolean("pending") == true}:" +
                editClipboard.lastModified().toString() + ":" +
                (if (editClipboard.isFile) JSONObject(editClipboard.readText()).optString("asset") else "")
        }
    }

    fun list(): JSONArray = locked {
        JSONArray().also { out ->
            drafts.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
                val doc = JSONObject(file.readText())
                val state = replay(doc)
                out.put(JSONObject().put("id", doc.getString("id"))
                    .put("name", state.optString("name", "未命名工程"))
                    .put("width", state.getInt("width")).put("height", state.getInt("height"))
                    .put("saved", archive(doc.getString("id")).exists())
                    .put("dirty", externalLink(doc.getString("id"))?.optBoolean("pending") == true ||
                        !archive(doc.getString("id")).exists() ||
                        File(documents, doc.getString("id") + ".sha256").let { marker ->
                            if (marker.isFile) marker.readText() != digest(doc.toString())
                            else file.lastModified() > archive(doc.getString("id")).lastModified()
                        })
                    .put("modified", file.lastModified()))
            }
        }
    }


    fun referenceList(p:JSONObject):JSONObject = locked {
        val snapshot=snapshot(loadCurrent())
        require(p.getString("documentId")==snapshot.getString("id")) { "工程已切换" }
        JSONObject().put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
            .put("references",JSONArray(ArtReferences.items(snapshot.getJSONObject("state"))))
            .put("selectedIds",JSONArray(ArtReferences.ids(snapshot.getJSONObject("state"))))
            .put("coordinateSpace","document").put("storage","embedded-or-linked-snapshot").put("exported",false)
            .put("referenceInfo",ArtReferenceFiles.info())
    }
    fun referenceAdd(actor:String,p:JSONObject):JSONObject = locked {referenceImage(actor,p)}
    private fun referenceImage(actor:String,p:JSONObject,replacing:JSONObject?=null):JSONObject {
        val before=snapshot(loadCurrent())
        require(p.getString("documentId")==before.getString("id") &&
            p.getInt("expectedRevision")==before.getInt("revision")) { "工程已切换或更新，请刷新" }
        val state=before.getJSONObject("state")
        require(replacing!=null||ArtReferences.items(state).size<ArtReferences.MAX) { "一个工程最多16张参考图像" }
        if(replacing!=null)require(!replacing.getBoolean("locked")) {"请先解锁参考图像"}
        val (bitmap,metadata)=ArtImagePolicy.decode(imageBytes(p.getString("base64")),p.optJSONObject("confirmResize"),
            ArtImagePolicy.renderBytes(this,state,state.getInt("width"),state.getInt("height"))+
                ArtReferences.MAX*ArtReferences.PREVIEW_EDGE.toLong()*ArtReferences.PREVIEW_EDGE*8)
        val w=bitmap.width;val h=bitmap.height
        val png=try { ArtImagePolicy.encodePng(bitmap,MAX_ASSET_BYTES) } finally { bitmap.recycle() }
        val id=replacing?.getString("id") ?: UUID.randomUUID().toString();val asset=UUID.randomUUID().toString()
        val scale=minOf(1.0,state.getInt("height")*0.5/h)
        val transform=if(p.has("matrix")) p.getJSONArray("matrix")
            else JSONArray(listOf(scale,0.0,0.0,scale,state.getInt("width")+32.0,0.0))
        val raw=if(replacing==null)JSONObject() else JSONObject(replacing.toString())
        raw.put("id",id).put("asset",asset).put("width",w).put("height",h).put("matrix",transform)
            .put("name",p.optString("name",replacing?.getString("name") ?: "参考图像"))
        if(p.has("externalSource"))raw.put("externalSource",p.getString("externalSource"))
        val ref=ArtReferences.normalize(raw)
        atomicBytes(assetFile(asset),png)
        try {
            return apply(actor,if(replacing==null)"REFERENCE_ADD" else "REFERENCE_REPLACE",JSONObject().put("documentId",p.getString("documentId"))
                .put("expectedRevision",p.getInt("expectedRevision")).put("reference",ref))
                .put("imageImport",metadata).put("referenceId",id)
        } catch(error:Throwable) { assetFile(asset).delete();throw error }
    }
    private fun referenceRequest(p:JSONObject):JSONObject {
        val snap=current();require(p.getString("documentId")==snap.getString("id")&&p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
        return snap
    }
    fun referenceLink(actor:String,p:JSONObject):JSONObject = locked {
        referenceRequest(p)
        val source=ArtReferenceFiles.location(p.getString("location")).toString()
        val q=JSONObject(p.toString())
        if(!q.has("base64"))q.put("base64",Base64.encodeToString(ArtReferenceFiles.read(source),Base64.NO_WRAP))
        if(!p.optBoolean("embedded",false))q.put("externalSource",source)
        referenceImage(actor,q)
    }
    fun referenceRefresh(actor:String,p:JSONObject):JSONObject = locked {
        val state=referenceRequest(p).getJSONObject("state")
        val ref=ArtReferences.items(state).firstOrNull {it.getString("id")==p.getString("id")} ?: error("参考图像不存在")
        require(ref.has("externalSource")) {"此参考没有外部链接"};require(!ref.getBoolean("locked")) {"请先解锁参考图像"}
        val q=JSONObject(p.toString()).put("matrix",ref.getJSONArray("matrix"))
        if(!q.has("base64"))q.put("base64",Base64.encodeToString(ArtReferenceFiles.read(ref.getString("externalSource")),Base64.NO_WRAP))
        referenceImage(actor,q,ref)
    }
    fun referencePaste(actor:String,p:JSONObject):JSONObject = locked {
        referenceRequest(p)
        val q=JSONObject(p.toString())
        if(!q.has("base64")) {
            val clip=clipboardInfo();require(clip.has("asset")) {"画室剪贴板没有图片；系统剪贴板请在手机前台粘贴"}
            val file=assetFile(clip.getString("asset"));require(file.isFile&&file.length() in 1..ArtReferenceFiles.IMAGE_BYTES.toLong()) {"画室剪贴板图片丢失或超限"}
            q.put("base64",Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
        }
        referenceImage(actor,q).put("clipboardSource",if(p.has("base64"))"supplied-image" else "studio")
    }
    fun referenceCapture(actor:String,p:JSONObject):JSONObject = locked {
        val snap=referenceRequest(p);val source=p.getString("source");require(source in setOf("layer","visible"))
        val state=JSONObject(snap.getJSONObject("state").toString())
        if(source=="layer") {
            val id=p.optString("layerId",state.getString("selectedLayerId"))
            val target=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==id} ?: error("源图层不存在")
            require(target.getString("kind")!="colorize_mask") {"请使用上色蒙版的父图层生成参考"}
            val keep=ArtMenuOperations.subtree(state,id).map {it.getString("id")}.toMutableSet()
            val forceVisible=mutableSetOf(id)
            var node=target;var depth=0
            while(node.optString("parentId").isNotEmpty()) {
                require(++depth<=ArtMenuOperations.layers(state).size)
                node=ArtMenuOperations.layers(state).first {it.getString("id")==node.getString("parentId")};keep.add(node.getString("id"));forceVisible.add(node.getString("id"))
            }
            ArtMenuOperations.layers(state).forEach {layer->layer.put("visible",layer.getString("id") in keep &&
                (layer.getString("id") in forceVisible||layer.getBoolean("visible")))}
            state.put("background","#00000000")
        }
        val frame=JSONObject(snap.toString()).put("state",state)
        val bitmap=ArtRenderer.render(this,frame,maxEdge=if(p.has("maxEdge"))p.getInt("maxEdge") else null)
        val bytes=try {ArtImagePolicy.encodePng(bitmap,MAX_ASSET_BYTES)} finally {bitmap.recycle()}
        referenceImage(actor,JSONObject(p.toString()).put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
            .put("generatedReference",JSONObject().put("source",source).put("sourceRevision",snap.getInt("revision"))
                .put("includesReferences",false).put("includesAssistants",false).put("canvasExtent",true))
    }
    fun referenceCollectionBytes(p:JSONObject):ByteArray = locked {
        val state=referenceRequest(p).getJSONObject("state");val all=ArtReferences.items(state)
        val ids=if(p.has("ids"))ArtShapes.ids(p.getJSONArray("ids")) else all.map {it.getString("id")}
        require(ids.isNotEmpty()&&ids.distinct().size==ids.size&&ids.all {id->all.any {it.getString("id")==id}}) {"请选择有效参考图像"}
        ArtImagePolicy.requireBytes(4L*ArtReferenceFiles.COLLECTION_BYTES,"参考集合导出")
        ArtReferenceFiles.encode(all.filter {it.getString("id") in ids},p.optBoolean("keepLinks",false)) {asset->
            val file=assetFile(asset);require(file.length() in 1..ArtReferenceFiles.IMAGE_BYTES.toLong());file.readBytes()}
    }
    fun referenceCollectionExport(p:JSONObject):JSONObject = locked {
        val bytes=referenceCollectionBytes(p)
        val name=p.optString("fileName","Reference-${System.currentTimeMillis()}.ailrefs")
        require(name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.ailrefs"))) {"文件名须为字母/数字/下划线/连字符加.ailrefs"}
        val file=File(exportDirectory(),name);require(!file.exists()) {"同名集合已存在，请换文件名"}
        atomicBytes(file,bytes)
        JSONObject().put("documentId",p.getString("documentId")).put("revision",p.getInt("expectedRevision"))
            .put("collectionExport",JSONObject().put("path",file.absolutePath).put("bytes",bytes.size)
                .put("keepLinks",p.optBoolean("keepLinks",false)).put("format",ArtReferenceFiles.FORMAT))
    }
    fun referenceCollectionImport(actor:String,p:JSONObject):JSONObject = locked {
        val state=referenceRequest(p).getJSONObject("state")
        val text=p.getString("base64");require(text.length<=2*ArtReferenceFiles.COLLECTION_BYTES) {"集合输入超过32 MiB"}
        val reserve=6L*ArtReferenceFiles.COLLECTION_BYTES+ArtImagePolicy.renderBytes(this,state,state.getInt("width"),state.getInt("height"))
        ArtImagePolicy.requireBytes(reserve,"参考集合导入")
        val collection=ArtReferenceFiles.decode(Base64.decode(text,Base64.DEFAULT))
        require(ArtReferences.items(state).size+collection.references.size<=ArtReferences.MAX) {"参考集合超过工程16张上限"}
        val prepared=collection.references.map {source->
            val q=JSONObject(source.toString());val data=collection.images.getValue(q.getString("image"));q.remove("image")
            if(!p.optBoolean("keepLinks",false))q.remove("externalSource")
            q.put("id",UUID.randomUUID().toString()).put("asset",UUID.randomUUID().toString())
            val normalized=ArtReferences.normalize(q)
            ArtImagePolicy.requireBytes(reserve+normalized.getInt("width").toLong()*normalized.getInt("height")*8,"参考集合图片验证")
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true;inScaled=false}
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth==q.getInt("width")&&bounds.outHeight==q.getInt("height")) {"集合图片与尺寸声明不一致"}
            val image=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply {inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888})
                ?: error("参考集合PNG无法解码")
            try {require(image.width==q.getInt("width")&&image.height==q.getInt("height")) {"集合图片与尺寸声明不一致"}} finally {image.recycle()}
            normalized to data
        }
        try {
            prepared.forEach {(ref,bytes)->atomicBytes(assetFile(ref.getString("asset")),bytes)}
            apply(actor,"REFERENCE_BATCH_ADD",JSONObject(p.toString()).put("references",JSONArray(prepared.map {it.first})))
                .put("referenceIds",JSONArray(prepared.map {it.first.getString("id")})).put("collectionImported",prepared.size)
        } catch(error:Throwable) {prepared.forEach {assetFile(it.first.getString("asset")).delete()};throw error}
    }
    fun referenceBitmaps(snapshot:JSONObject):Map<String,Bitmap> = locked {
        val map=mutableMapOf<String,Bitmap>()
        try {
            for(r in ArtReferences.items(snapshot.getJSONObject("state"))) {
                val asset=r.getString("asset")
                if(asset in map) continue
                val file=assetFile(asset)
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true;inScaled=false }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
                ArtImagePolicy.requireDimensions(bounds.outWidth,bounds.outHeight)
                var sample=1
                while((maxOf(bounds.outWidth,bounds.outHeight)+sample-1)/sample>ArtReferences.PREVIEW_EDGE) sample*=2
                val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply {
                        inScaled=false;inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
                    }) ?: error("参考图像资源无效")
                map[asset]=bitmap
            }
            map
        } catch(error:Throwable) { map.values.forEach { it.recycle() };throw error }
    }

    fun referenceRegion(p:JSONObject):JSONObject = locked {
        val snapshot=snapshot(loadCurrent())
        require(p.getString("documentId")==snapshot.getString("id")) { "工程已切换" }
        if(p.has("expectedRevision")) require(p.getInt("expectedRevision")==snapshot.getInt("revision")) { "工程已更新" }
        val ref=ArtReferences.items(snapshot.getJSONObject("state")).firstOrNull { it.getString("id")==p.getString("id") }
            ?: error("参考图像不存在")
        val x=p.getInt("x");val y=p.getInt("y");val w=p.getInt("width");val h=p.getInt("height")
        val edge=p.optInt("maxEdge",512)
        require(x>=0 && y>=0 && w>0 && h>0 && x.toLong()+w<=ref.getInt("width") &&
            y.toLong()+h<=ref.getInt("height") && edge in 64..2048) { "参考图像局部范围无效" }
        var sample=1
        while((maxOf(w,h)+sample-1)/sample>edge) sample*=2
        ArtImagePolicy.requireBytes(edge.toLong()*edge*16,"参考图像局部预览")
        val decoder=android.graphics.BitmapRegionDecoder.newInstance(assetFile(ref.getString("asset")).absolutePath,false)
            ?: error("参考图像区域解码器不可用")
        val bitmap=try { decoder.decodeRegion(android.graphics.Rect(x,y,x+w,y+h),
            android.graphics.BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 })
            ?: error("无法解码参考图像区域") } finally { decoder.recycle() }
        try {
            val encoded=Base64.encodeToString(ArtImagePolicy.encodePng(bitmap,8*1024*1024),Base64.NO_WRAP)
            JSONObject().put("regionPreview",JSONObject().put("referenceId",ref.getString("id"))
                .put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
                .put("coordinateSpace","source-pixels").put("x",x).put("y",y).put("width",w).put("height",h)
                .put("imageWidth",bitmap.width).put("imageHeight",bitmap.height).put("rawSource",true))
                .put("mcp_content",JSONArray().put(JSONObject().put("type","image").put("mimeType","image/png").put("data",encoded)))
        } finally { bitmap.recycle() }
    }

    fun referencePreview(p:JSONObject):JSONObject = locked {
        val snapshot=snapshot(loadCurrent())
        require(p.getString("documentId")==snapshot.getString("id")) { "工程已切换" }
        if(p.has("expectedRevision")) require(p.getInt("expectedRevision")==snapshot.getInt("revision")) { "工程已更新" }
        ArtReferencePreview.overview(this,snapshot)
    }

    fun pathCreate(actor:String,p:JSONObject):JSONObject = apply(actor,"SHAPE_CREATE",JSONObject()
        .put("documentId",p.getString("documentId")).put("expectedRevision",p.getInt("expectedRevision"))
        .put("layerId",p.getString("layerId")).put("shape",ArtPathGeometry.create(p)))

    fun pathNodes(p:JSONObject):JSONObject = locked {
        val snapshot=snapshot(loadCurrent());require(p.getString("documentId")==snapshot.getString("id")) { "工程已切换" }
        val state=snapshot.getJSONObject("state");val layer=ArtShapes.layer(state,p.getString("layerId"))
        val shape=ArtShapes.items(layer).firstOrNull { it.getString("id")==p.getString("id") }
            ?: error("路径对象不存在")
        val nodes=ArtPathGeometry.nodes(shape)
        val transform=ArtShapes.layerMatrix(state,layer).apply { preConcat(ArtShapes.matrix(shape.getJSONArray("matrix"))) }
        JSONObject().put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
            .put("layerId",layer.getString("id")).put("id",shape.getString("id"))
            .put("nodes",ArtPathGeometry.json(nodes)).put("closed",shape.getBoolean("closed")).put("subpaths",ArtPathTopology.describe(shape))
            .put("objectToDocument",ArtShapes.encode(transform)).put("coordinateSpace","object-local")
            .put("locked",ArtMenuOperations.isLocked(state,layer)||shape.getBoolean("locked"))
            .put("visible",ArtShapes.visible(state,layer)&&shape.getBoolean("visible")&&shape.getDouble("opacity")>0.0)
    }

    fun calligraphy(actor:String,p:JSONObject):JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id")&&p.getInt("expectedRevision")==snap.getInt("revision")) {"工程或版本已变化，请重新读取"}
        val options=JSONObject(p.toString())
        if(p.has("profileId")) {
            val saved=calligraphyProfile(p.getString("profileId")).getJSONObject("settings")
            for(key in ArtCalligraphy.settingKeys)if(!p.has(key))options.put(key,saved.get(key))
        }
        val guide=ArtCalligraphy.guide(snap.getJSONObject("state"),p.getString("layerId"),options)
        val shape=ArtCalligraphy.create(options,guide)
        apply(actor,"SHAPE_CREATE",JSONObject().put("documentId",p.getString("documentId"))
            .put("expectedRevision",p.getInt("expectedRevision")).put("layerId",p.getString("layerId")).put("shape",shape))
    }

    private fun calligraphyProfileFile()=File(root,"calligraphy-profiles.json")
    fun calligraphyProfiles():JSONObject=locked {
        val list=readBrushList(calligraphyProfileFile())
        require(list.length()<=128)
        val seen=mutableSetOf<String>()
        for(i in 0 until list.length()) {
            val item=list.getJSONObject(i);val id=item.getString("id");validateId(id);require(seen.add(id))
            require(item.getString("name").length in 1..64)
            val settings=item.getJSONObject("settings");require(settings.keys().asSequence().all {it in ArtCalligraphy.settingKeys})
            item.put("settings",ArtCalligraphy.settings(settings))
        }
        JSONObject().put("profiles",list)
    }
    fun calligraphyProfile(id:String):JSONObject=locked {
        validateId(id);val list=calligraphyProfiles().getJSONArray("profiles")
        (0 until list.length()).map {list.getJSONObject(it)}.firstOrNull {it.getString("id")==id} ?: error("书法配置档不存在")
    }
    fun saveCalligraphyProfile(p:JSONObject):JSONObject=locked {
        val id=if(p.has("id"))p.getString("id") else UUID.randomUUID().toString();validateId(id)
        val name=p.getString("name").trim();require(name.length in 1..64)
        val settings=p.getJSONObject("settings");require(settings.keys().asSequence().all {it in ArtCalligraphy.settingKeys}) {"配置档只保存书法参数，不保存工程或路径引用"}
        val item=JSONObject().put("id",id).put("name",name).put("settings",ArtCalligraphy.settings(settings))
        val old=calligraphyProfiles().getJSONArray("profiles");val out=JSONArray()
        for(i in 0 until old.length())if(old.getJSONObject(i).getString("id")!=id)out.put(old.getJSONObject(i))
        require(out.length()<128) {"书法配置档最多128个"};out.put(item)
        atomic(calligraphyProfileFile(),out.toString());item
    }
    fun deleteCalligraphyProfile(id:String):JSONObject=locked {
        validateId(id);val old=calligraphyProfiles().getJSONArray("profiles");val out=JSONArray();var found=false
        for(i in 0 until old.length()) {val item=old.getJSONObject(i);if(item.getString("id")==id)found=true else out.put(item)}
        require(found) {"书法配置档不存在"};atomic(calligraphyProfileFile(),out.toString());JSONObject().put("deleted",id)
    }

    fun freehand(actor:String, params:JSONObject):JSONObject {
        // Fit on the caller's worker thread; revision checks and the atomic write remain in apply.
        val shape=ArtFreehand.create(params)
        val p=JSONObject().put("documentId",params.getString("documentId"))
            .put("expectedRevision",params.getInt("expectedRevision")).put("layerId",params.getString("layerId")).put("shape",shape)
        for(key in listOf("startEndpoint","endEndpoint"))if(params.has(key))p.put(key,JSONObject(params.getJSONObject(key).toString()))
        return apply(actor,"SHAPE_FREEHAND",p)
    }



    private fun selectionRequest(p: JSONObject): JSONObject {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id")) {"工程已切换，请重新读取选区"}
        if(p.has("expectedRevision"))require(p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已更新，请重新读取选区"}
        return snap
    }
    /** Reference layers retain their document transforms; helpers and background are excluded. */
    private fun selectionReference(snap: JSONObject,p: JSONObject,workingBytes: Long): android.graphics.Bitmap {
        val view=JSONObject(snap.toString());val state=view.getJSONObject("state");val all=ArtMenuOperations.layers(state)
        val layerId=p.getString("layerId");val target=all.firstOrNull {it.getString("id")==layerId} ?: error("参考图层不存在")
        require(p.getString("reference") in setOf("current","visible","labels"))
        state.put("background","#00000000")
        if(p.getString("reference")=="current") {
            require(target.getString("kind")!="group") {"当前层参考需要实际内容图层，请选择绘画、图像、文字或矢量层"}
            val included=mutableSetOf(layerId);var parent=target.optString("parentId")
            repeat(all.size+1) {
                if(parent.isNotBlank()) {
                    require(included.add(parent)) {"图层组循环引用"}
                    val group=all.first {it.getString("id")==parent};parent=group.optString("parentId")
                }
            }
            require(parent.isBlank())
            for(layer in all) {
                layer.put("visible",layer.getString("id") in included)
                if(layer.getString("id") in included)layer.put("opacity",1.0).put("blend","normal")
            }
        } else if(p.getString("reference")=="labels") {
            val included=ArtLayerLabels.referenceIds(state,ArtLayerLabels.parse(p.getJSONArray("colorLabels")))
            for(layer in all)layer.put("visible",layer.getString("id") in included)
        }
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this,state,state.getInt("width"),state.getInt("height"))+workingBytes,"选区参考与搜索")
        return ArtRenderer.render(this,view)
    }
    private fun selectionToolRequest(p: JSONObject): JSONObject {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请重新建立选区"}
        return snap
    }
    fun basicSelection(actor:String,p:JSONObject):JSONObject = locked {
        val snap=current();val state=snap.getJSONObject("state")
        if(p.has("documentId"))require(p.getString("documentId")==snap.getString("id")) {"工程已切换，请重新建立选区"}
        if(p.has("expectedRevision"))require(p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已更新，请重新建立选区"}
        val shape=p.optString("shape","rect");require(shape in setOf("rect","ellipse","polygon","freehand"))
        val geometry=if(shape in setOf("polygon","freehand"))ArtSelection.fromVertices(p.getJSONArray("points")) else
            JSONObject().put("shape",shape).put("x",p.getDouble("x")).put("y",p.getDouble("y")).put("width",p.getDouble("width")).put("height",p.getDouble("height"))
        val options=ArtSoftSelection.options(p)
        val created=ArtSoftSelection.process(geometry,options,state.getInt("width"),state.getInt("height"))
        val selection=ArtSoftSelection.combine(state.optJSONObject("selection"),created,options.getString("mode"),state.getInt("width"),state.getInt("height"))
        apply(actor,"SELECTION_TOOL",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
            .put("selection",selection).put("tool",shape)).put("selectionFeedback",true)
    }
    fun adjustSelection(actor:String,p:JSONObject):JSONObject = locked {
        val snap=selectionToolRequest(p);val state=snap.getJSONObject("state")
        require(!p.has("antialias") && !p.has("mode")) {"调整已有蒙版仅支持expand和feather；抗锯齿请在创建时设置"}
        val selected=state.optJSONObject("selection") ?: error("当前没有选区")
        val selection=ArtSoftSelection.process(selected,p,state.getInt("width"),state.getInt("height"))
        apply(actor,"SELECTION_TOOL",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
            .put("selection",selection).put("tool","adjust")).put("selectionFeedback",true)
    }
    fun selectionCoverage(p:JSONObject):JSONObject = locked {
        val snap=selectionToolRequest(p);val s=snap.getJSONObject("state").optJSONObject("selection") ?: error("当前没有选区")
        val x=p.getDouble("x");val y=p.getDouble("y");require(x.isFinite()&&y.isFinite())
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("coverage",ArtSoftSelection.Sampler(s).at(x,y)).put("range","0..255")
    }
    fun colorSelection(actor: String,p: JSONObject,connected: Boolean): JSONObject = locked {
        val snap=selectionToolRequest(p);val state=snap.getJSONObject("state");val o=ArtColorSelection.options(p)
        val request=JSONObject(p.toString());o.keys().forEach {request.put(it,o.get(it))}
        val bounds=ArtColorSelection.bounds(state,request)
        val bitmap=selectionReference(snap,request,bounds.width().toLong()*bounds.height()*40)
        val result=try {ArtColorSelection.solve(state,bitmap,request,connected)} finally {bitmap.recycle()}
        val selection=ArtBezierSelection.combine(state.optJSONObject("selection"),result.selection,o.getString("mode"),snap.getJSONObject("state").getInt("width"),snap.getJSONObject("state").getInt("height"))
        apply(actor,"SELECTION_TOOL",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
            .put("selection",selection).put("tool",if(connected)"contiguous" else "similar"))
            .put("selectedPixels",result.pixels).put("coverageSum",result.coverageSum).put("sampledColor",result.sampledColor).put("selectionFeedback",true)
    }
    fun magneticReference(p: JSONObject): ArtMagneticSelection.Image = locked {
        val snap=selectionToolRequest(p);val o=ArtMagneticSelection.options(p)
        val request=JSONObject(p.toString());o.keys().forEach {request.put(it,o.get(it))}
        val bounds=ArtColorSelection.bounds(snap.getJSONObject("state"),request)
        val bitmap=selectionReference(snap,request,bounds.width().toLong()*bounds.height()*16+ArtMagneticSelection.MAX_SEARCH_PIXELS*64L)
        try {ArtMagneticSelection.image(snap,bitmap,request)} finally {bitmap.recycle()}
    }
    fun magneticTrace(p: JSONObject): JSONObject = locked {
        val image=magneticReference(p);val points=ArtMagneticSelection.trace(image,ArtMagneticSelection.anchors(p.getJSONArray("anchors")),p,p.optBoolean("closed",false))
        JSONObject().put("documentId",image.documentId).put("revision",image.revision).put("points",ArtMagneticSelection.json(points))
            .put("closed",p.optBoolean("closed",false)).put("algorithm","rgba-sobel-live-wire")
    }
    fun magneticCreate(actor: String,p: JSONObject): JSONObject = locked {
        val image=magneticReference(p)
        val points=ArtMagneticSelection.trace(image,ArtMagneticSelection.anchors(p.getJSONArray("anchors")),p,true)
        magneticCommit(actor,JSONObject(p.toString()).put("points",ArtMagneticSelection.json(points)))
    }
    fun magneticCommit(actor: String,p: JSONObject): JSONObject = locked {
        val snap=selectionToolRequest(p);val o=ArtMagneticSelection.options(p)
        val created=ArtSelection.fromVertices(p.getJSONArray("points"));ArtSelection.validate(created)
        val path=ArtSelection.path(created);val canonical=android.graphics.Path()
        check(canonical.op(path,path,android.graphics.Path.Op.UNION))
        require(!canonical.isEmpty) {"磁性路径没有围出有效面积"}
        val state=snap.getJSONObject("state");val w=state.getInt("width");val h=state.getInt("height")
        val mask=ArtSoftSelection.process(created,o,w,h)
        val selection=ArtCurveSoftSelection.append(state.optJSONObject("selection"),mask,o.getString("mode"),w,h)
        apply(actor,"SELECTION_TOOL",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
            .put("selection",selection).put("tool","magnetic")).put("selectionFeedback",true).put("algorithm","rgba-sobel-live-wire")
    }

    private fun comicRequest(p: JSONObject): JSONObject {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id")) {"工程已切换，请重新读取"}
        require(p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已更新，请刷新分格"}
        return snap
    }
    fun comicFrame(actor: String,p: JSONObject): JSONObject = locked {
        val snap=comicRequest(p)
        val shape=ArtComicPanels.frame(snap.getJSONObject("state"),p)
        apply(actor,"SHAPE_CREATE",JSONObject().put("documentId",snap.getString("id"))
            .put("expectedRevision",snap.getInt("revision")).put("layerId",p.getString("layerId")).put("shape",shape))
    }
    fun comicEdit(actor: String,mode: String,p: JSONObject): JSONObject = locked {
        require(mode in setOf("cut","merge"))
        val snap=comicRequest(p)
        val result=if(mode=="cut")ArtComicPanels.cut(snap.getJSONObject("state"),p)
            else ArtComicPanels.merge(snap.getJSONObject("state"),p)
        if(result==null)return@locked snap.put("comicPanelFeedback",true).put("changed",false)
            .put("message","分格线没有完整穿过可切分的边框，请从框外拖到框外")
        result.put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
        apply(actor,if(mode=="cut")"SHAPE_COMIC_CUT" else "SHAPE_COMIC_MERGE",result)
            .put("comicPanelFeedback",true).put("changed",true)
            .put("removedIds",result.getJSONArray("removedIds"))
            .put("createdIds",JSONArray().apply {
                val groups=result.getJSONArray("groups")
                for(i in 0 until groups.length()) {
                    val shapes=groups.getJSONObject(i).getJSONArray("shapes")
                    for(j in 0 until shapes.length())put(shapes.getJSONObject(j).getString("id"))
                }
            }).also {if(result.has("gutterWidth"))it.put("gutterWidth",result.getDouble("gutterWidth"))}
    }
    fun bezierSelectionCreate(actor: String,p: JSONObject): JSONObject = locked {
        val snap=selectionRequest(p);require(p.has("expectedRevision"))
        val created=ArtBezierSelection.fromNodes(p.getJSONArray("nodes"))
        val state=snap.getJSONObject("state")
        val selection=ArtCurveSoftSelection.create(state.optJSONObject("selection"),created,p,state.getInt("width"),state.getInt("height"))
        apply(actor,"SELECTION_BEZIER",JSONObject().put("documentId",snap.getString("id"))
            .put("expectedRevision",snap.getInt("revision")).put("selection",selection))
    }
    fun bezierSelectionEdit(actor: String,p: JSONObject): JSONObject = locked {
        val snap=selectionRequest(p);require(p.has("expectedRevision"))
        val current=snap.getJSONObject("state").optJSONObject("selection") ?: error("当前没有选区")
        val state=snap.getJSONObject("state")
        val selection=if(current.has("curveParts"))ArtCurveSoftSelection.edit(current,p.optInt("componentIndex",0),p.getJSONArray("edits"),state.getInt("width"),state.getInt("height"))
            else ArtBezierSelection.edited(current,p.optInt("componentIndex",0),p.getJSONArray("edits"))
        apply(actor,"SELECTION_BEZIER",JSONObject().put("documentId",snap.getString("id"))
            .put("expectedRevision",snap.getInt("revision")).put("selection",selection))
    }
    fun selectionPreview(p: JSONObject): JSONObject = locked {
        val snap=selectionRequest(p);val state=snap.getJSONObject("state")
        val image=ArtCanvasFeedback.preview(this,snap,0,0,state.getInt("width"),state.getInt("height"),256,"thumbnail",selectionOutline=true)
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("selection",state.optJSONObject("selection") ?: JSONObject.NULL)
            .put("thumbnail",image.getJSONObject("metadata")).put("mcp_content",JSONArray().put(image.getJSONObject("content")))
    }

    fun bezierSelectionNodes(p: JSONObject): JSONObject = locked {
        val snap=selectionRequest(p)
        val selection=snap.getJSONObject("state").optJSONObject("selection") ?: error("当前没有选区")
        val parts=ArtBezierSelection.parts(selection);val index=p.optInt("componentIndex",0)
        val component=ArtBezierSelection.component(selection,index)
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("coordinateSpace","document").put("componentIndex",index).put("componentCount",parts.size)
            .put("mode",parts[index].getString("mode")).put("options",parts[index].optJSONObject("options") ?: ArtSoftSelection.defaults().put("antialias",0)).put("closed",true)
            .put("nodes",ArtPathGeometry.json(ArtBezierSelection.nodes(component)))
    }

    fun colorizeList(p: JSONObject): JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id")) {"工程已切换"}
        if(p.has("expectedRevision"))require(p.getInt("expectedRevision")==snap.getInt("revision")) {"工程版本已更新"}
        val state=snap.getJSONObject("state")
        if(p.has("maskId"))ArtColorize.layer(state,p.getString("maskId"))
        val masks=ArtMenuOperations.layers(state).filter {it.getString("kind")=="colorize" && (!p.has("maskId") || it.getString("id")==p.getString("maskId"))}.map {
            val copy=JSONObject(it.toString()).put("dirty",ArtColorize.dirty(state,it)).put("canUpdate",ArtColorize.canUpdate(state,it))
                .put("sourceAvailable",ArtMenuOperations.layers(state).any {source-> source.getString("id")==it.getJSONObject("colorize").getString("sourceLayerId")})
            val data=copy.getJSONObject("colorize")
            data.put("settings",ArtColorize.normalizeSettings(data.getJSONObject("settings"),JSONObject()))
            if(!p.optBoolean("includeKeys",false)) {
                data.remove("keys")
                data.put("keySummaries",JSONArray(ArtColorize.items(it).map {key ->
                    JSONObject().put("id",key.getString("id")).put("color",key.getString("color"))
                        .put("erase",key.getBoolean("erase")).put("width",key.getDouble("width"))
                        .put("pointCount",key.getJSONArray("points").length())
                }))
            }
            copy
        }
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("selectedLayerId",state.optString("selectedLayerId")).put("masks",JSONArray(masks))
            .put("scope",ArtColorize.defaults())
    }
    fun colorizeCreate(actor: String,p: JSONObject): JSONObject =
        apply(actor,"COLORIZE_CREATE",JSONObject(p.toString()).put("id",UUID.randomUUID().toString()))
    fun colorizeStroke(actor: String,p: JSONObject): JSONObject = locked {
        val snap=current();val input=JSONObject(p.toString())
        snap.getJSONObject("state").optJSONObject("selection")?.let {input.put("selection",JSONObject(it.toString()))}
        apply(actor,"COLORIZE_STROKE",JSONObject(p.toString()).put("stroke",ArtColorize.stroke(input,UUID.randomUUID().toString())))
    }
    fun colorizeUpdate(actor: String,p: JSONObject): JSONObject = locked {
        require(actor in setOf("AWEI","LANER"))
        val snap=current();require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {
            "工程或版本已改变，请重新更新蒙版"
        }
        val state=snap.getJSONObject("state");val mask=ArtColorize.layer(state,p.getString("maskId"))
        require(!mask.getBoolean("locked") && mask.getBoolean("visible")) {"蒙版已锁定或隐藏"}
        ArtColorize.root(mask)
        val source=ArtColorize.source(state,mask.getJSONObject("colorize").getString("sourceLayerId"))
        val view=ArtMenuOperations.isolated(snap,setOf(source.getString("id")),source.getString("id"))
        // The full render and a conservative solver reservation coexist; do not estimate them independently.
        val bitmap=ArtRenderer.render(this,view)
        val result=try {
            val area=ArtColorizeSolver.region(state,bitmap,mask)
            ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this,view.getJSONObject("state"),
                state.getInt("width"),state.getInt("height"))+ArtColorizeSolver.workingBytes(mask,area.width().toLong()*area.height()),"上色蒙版更新")
            ArtColorizeSolver.solve(state,bitmap,mask,area)
        } finally {bitmap.recycle()}
        val outputWidth=result.bitmap.width;val outputHeight=result.bitmap.height
        val png=try {ArtImagePolicy.encodePng(result.bitmap,MAX_ASSET_BYTES)} finally {result.bitmap.recycle()}
        val asset=UUID.randomUUID().toString()
        try {
            atomicBytes(assetFile(asset),png)
            val params=JSONObject(p.toString()).put("generation",mask.getJSONObject("colorize").getInt("generation"))
                .put("sourceSignature",ArtColorize.signature(state,source))
                .put("output",JSONObject().put("asset",asset).put("x",result.x).put("y",result.y)
                    .put("width",outputWidth).put("height",outputHeight))
            apply(actor,"COLORIZE_OUTPUT",params).put("colorizeResult",JSONObject().put("filledPixels",result.filled)
                .put("seedPixels",result.seeds).put("algorithm","prefiltered-seeded-geodesic-fill")
                .put("cleanedPixels",result.cleanup.pixels).put("cleanedSeedPixels",result.cleanup.seedPixels)
                .put("cleanedRegions",result.cleanup.regions)
                .put("settings",ArtColorize.normalizeSettings(mask.getJSONObject("colorize").getJSONObject("settings"),JSONObject())))
        } catch(error:Throwable) {assetFile(asset).delete();throw error}
    }
    fun colorizePreview(p: JSONObject): JSONObject = locked {
        val snap=current();require(p.getString("documentId")==snap.getString("id"))
        if(p.has("expectedRevision"))require(p.getInt("expectedRevision")==snap.getInt("revision"))
        val state=snap.getJSONObject("state")
        if(p.has("maskId")) {
            ArtColorize.layer(state,p.getString("maskId"));state.put("selectedLayerId",p.getString("maskId"))
        }
        val preview=ArtCanvasFeedback.preview(this,snap,0,0,state.getInt("width"),state.getInt("height"),
            256,"thumbnail",colorizeKeys=true)
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("thumbnail",preview.getJSONObject("metadata")).put("mcp_content",JSONArray().put(preview.getJSONObject("content")))
    }

    fun assistantList(p: JSONObject): JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id")) { "工程已切换" }
        if(p.has("expectedRevision")) require(p.getInt("expectedRevision")==snap.getInt("revision")) { "工程版本已更新" }
        val state=snap.getJSONObject("state")
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("assistants",JSONArray(ArtAssistants.items(state)))
            .put("selectedId",ArtAssistants.selected(state)).put("settings",ArtAssistants.settings(state))
            .put("types",JSONObject(ArtAssistants.types)).put("typeInfos",ArtAssistants.typeInfo()).put("pending",JSONArray(ArtAssistants.pending))
            .put("units",JSONObject(ArtAssistantGeometry.units)).put("unitPolicy","fixedLength: ruler only; physical units use saved per-guide unitDpi, default96; no print DPI change")
            .put("defaults",JSONObject().put("localEnabled",false).put("fixedLength",0).put("lengthUnit","px").put("unitDpi",96).put("useVertical",true))
            .put("coordinateSpace","document").put("exported",false)
            .put("supportedBrushTools",JSONArray(ArtAssistants.brushTools.toList()))
    }

    fun assistantProject(p: JSONObject): JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id") &&
            p.getInt("expectedRevision")==snap.getInt("revision")) { "工程已切换或更新，请刷新" }
        val source=ArtAssistants.items(snap.getJSONObject("state")).firstOrNull { it.getString("id")==p.getString("id") }
            ?: error("尺规不存在")
        require(source.getBoolean("visible") && source.getBoolean("enabled")) { "尺规隐藏或吸附已禁用" }
        val raw=p.getJSONArray("points");require(raw.length() in 1..10000)
        val samples=(0 until raw.length()).map { i ->
            val q=raw.getJSONArray(i)
            require(q.length() in 2..(if(ArtBrush.supports(p.optString("tool","ink")))6 else 3) && q.getDouble(0).isFinite() && q.getDouble(1).isFinite() &&
                kotlin.math.abs(q.getDouble(0))<=1_000_000 && kotlin.math.abs(q.getDouble(1))<=1_000_000)
            if(q.length()>=3) require(q.getDouble(2) in 0.0..1.0)
            AssistantPoint(q.getDouble(0),q.getDouble(1))
        }
        val projection=ArtAssistants.Projection(source,samples.first())
        val output=JSONArray()
        samples.forEachIndexed { i,q -> val next=projection.project(q).json()
            for(n in 2 until raw.getJSONArray(i).length())next.put(raw.getJSONArray(i).getDouble(n))
            output.put(next)
        }
        JSONObject().put("documentId",snap.getString("id")).put("revision",snap.getInt("revision"))
            .put("assistantId",projection.id).put("coordinateSpace","document").put("points",output)
    }

    fun assistantStroke(actor: String,p: JSONObject): JSONObject = locked {
        val snap=current()
        val projected=if(p.optString("tool","ink") in setOf("dyna","line")) {
            require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
            // Dynamic filtering and straight-line geometry resolve their own document-space guide projection.
            JSONObject().put("points",p.getJSONArray("points")).put("assistantId",p.getString("id"))
        } else assistantProject(p)
        val state=snap.getJSONObject("state")
        val layer=ArtMenuOperations.layers(state).first { it.getString("id")==p.getString("layerId") }
        require(layer.getString("kind")=="paint") { "尺规绘画需要绘画图层" }
        val inverse=android.graphics.Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse))
        val raw=projected.getJSONArray("points");val output=JSONArray()
        for(i in 0 until raw.length()) {
            val q=raw.getJSONArray(i);val v=floatArrayOf(q.getDouble(0).toFloat(),q.getDouble(1).toFloat())
            inverse.mapPoints(v)
            val point=JSONArray().put(v[0]).put(v[1]);for(n in 2 until q.length())point.put(q.getDouble(n));output.put(point)
        }
        val tool=p.optString("tool","ink");require(tool in ArtAssistants.brushTools) { "此画笔尚未实现尺规吸附" }
        val stroke=JSONObject(p.toString()).put("id",UUID.randomUUID().toString()).put("tool",tool)
            .put("assistantId",projected.getString("assistantId")).put("points",output)
            .put("width",p.getDouble("width"))
        apply(actor,"STROKE_ADD",stroke)
    }

    fun rasterPathGeometry(p:JSONObject):JSONObject = locked {
        val snap=snapshot(loadCurrent());require(p.getString("documentId")==snap.getString("id")&&p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已改变，请刷新"}
        val state=snap.getJSONObject("state");val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(layer.getString("kind")=="paint");ArtShapes.layerMatrix(state,layer)
        val geometry=ArtRasterPath.normalize(p)
        if(geometry.getString("outline")=="brush")geometry.put("outlinePoints",ArtRasterPath.outlinePoints(geometry))
        geometry.put("coordinateSpace","layer-local").put("revision",snap.getInt("revision"))
    }
    fun rasterPathDraw(actor:String,p:JSONObject):JSONObject = locked {
        val snap=snapshot(loadCurrent());require(p.getString("documentId")==snap.getString("id")&&p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已改变，请刷新"}
        val state=snap.getJSONObject("state");val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(layer.getString("kind")=="paint"&&!ArtMenuOperations.isLocked(state,layer)&&ArtShapes.visible(state,layer)) {"请选择可编辑的绘画图层"}
        apply(actor,"STROKE_ADD",JSONObject(p.toString()).put("id",UUID.randomUUID().toString()))
    }
    fun figureGeometry(p:JSONObject):JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
        ArtShapes.layerMatrix(snap.getJSONObject("state"),ArtMenuOperations.layers(snap.getJSONObject("state")).first {it.getString("id")==p.getString("layerId")})
        ArtFigure.geometry(p).put("coordinateSpace","layer-local").put("revision",snap.getInt("revision"))
    }
    fun figureDraw(actor:String,p:JSONObject):JSONObject = locked {
        val snap=current();val state=snap.getJSONObject("state")
        require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(layer.getString("kind") in setOf("paint","vector") && !ArtMenuOperations.isLocked(state,layer) && ArtShapes.visible(state,layer)) {"请选择可编辑的绘画或矢量图层"}
        if(layer.getString("kind")=="paint")apply(actor,"STROKE_ADD",JSONObject(p.toString()).put("id",UUID.randomUUID().toString()))
        else {
            require(!p.has("brush")&&!p.has("brushPresetId")) {"矢量形状不使用栅格笔刷配置"}
            val geometry=ArtFigure.geometry(p);val fill=geometry.getJSONObject("figureFill")
            require(fill.getString("mode")!="pattern") {"图案填充请使用绘画图层"}
            val shape=JSONObject().put("id",UUID.randomUUID().toString()).put("kind",geometry.getString("tool"))
                .put("points",geometry.getJSONArray("figureCorners")).put("cornerRadius",geometry.getDouble("effectiveRadius"))
                .put("stroke",if(geometry.getString("outline")=="none")"#00000000" else p.getString("color"))
                .put("strokeWidth",p.getDouble("width")).put("opacity",p.optDouble("opacity",1.0))
                .put("fill",if(fill.getString("mode")=="solid")fill.getString("color") else "#00000000")
            apply(actor,"SHAPE_CREATE",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
                .put("layerId",p.getString("layerId")).put("shape",shape))
        }
    }

    fun lineGeometry(p:JSONObject):JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
        ArtLine.geometry(p,snap.getJSONObject("state")).put("coordinateSpace","layer-local")
            .put("revision",snap.getInt("revision"))
    }
    fun lineDraw(actor:String,p:JSONObject):JSONObject = locked {
        val snap=current();val state=snap.getJSONObject("state")
        require(p.getString("documentId")==snap.getString("id") && p.getInt("expectedRevision")==snap.getInt("revision")) {"工程已切换或更新，请刷新"}
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        require(layer.getString("kind") in setOf("paint","vector") && !ArtMenuOperations.isLocked(state,layer) && ArtShapes.visible(state,layer)) {"请选择可编辑的绘画或矢量图层"}
        if(layer.getString("kind")=="paint")apply(actor,"STROKE_ADD",JSONObject(p.toString()).put("tool","line").put("id",UUID.randomUUID().toString()))
        else {
            require(!p.has("brushPresetId") && !p.has("brush")) {"矢量直线不使用栅格笔刷配置"}
            val line=ArtLine.geometry(p,state)
            val endpoints=ArtBrush.samples(line.getJSONArray("lineEndpoints"))
            val shape=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","line").put("points",JSONArray(endpoints.map {JSONArray().put(it.x).put(it.y)}))
                .put("stroke",p.getString("color")).put("strokeWidth",p.getDouble("width"))
                .put("opacity",p.optDouble("opacity",1.0)).put("fill","#00000000")
            apply(actor,"SHAPE_CREATE",JSONObject().put("documentId",snap.getString("id")).put("expectedRevision",snap.getInt("revision"))
                .put("layerId",p.getString("layerId")).put("shape",shape))
        }
    }

    fun assistantPreview(p: JSONObject): JSONObject = locked {
        val snap=current()
        require(p.getString("documentId")==snap.getString("id"))
        if(p.has("expectedRevision")) require(p.getInt("expectedRevision")==snap.getInt("revision"))
        ArtReferencePreview.overview(this,snap,includeAssistants=true)
    }

    private fun brushPresetFile()=File(root,"brush-presets.json")
    private fun brushResourceFile()=File(root,"brush-resources.json")
    private fun readBrushList(file:File)=if(file.exists())JSONArray(file.readText()) else JSONArray()
    private fun builtInBrushPresets():List<JSONObject> {
        val presets=ArtBrush.tools.map { (tool,label)->JSONObject().put("id","builtin:"+tool).put("name",label)
            .put("tool",tool).put("width",if(tool=="spray")40 else 6).put("opacity",1).put("builtin",true).put("brush",ArtBrush.defaults(tool)) }.toMutableList()
        presets.add(JSONObject().put("id","builtin:pixel").put("name","像素铅笔").put("tool","pencil").put("width",1).put("opacity",1).put("builtin",true)
            .put("brush",ArtBrush.settings("pencil",JSONObject("""{"flow":1,"tip":{"shape":"square"},"texture":{"kind":"none"},"dynamics":{"size":{"enabled":false}},"smoothing":{"mode":"pixel_perfect"}}"""))))
        presets.add(JSONObject().put("id","builtin:stable").put("name","稳定线稿").put("tool","ink").put("width",6).put("opacity",1).put("builtin",true)
            .put("brush",ArtBrush.settings("ink",JSONObject("""{"smoothing":{"mode":"stabilizer","delay":12}}"""))))
        return presets
    }
    fun brushPresets(tool:String?=null,full:Boolean=false):JSONObject=locked {
        require(tool==null || tool in ArtBrush.tools)
        val all=builtInBrushPresets()+readBrushList(brushPresetFile()).let { a->(0 until a.length()).map {a.getJSONObject(it)} }
        val result=JSONArray()
        all.filter {tool==null || it.getString("tool")==tool}.forEach { preset->
            val item=JSONObject(preset.toString());if(!full)item.remove("brush");result.put(item)
        };JSONObject().put("presets",result)
    }
    fun brushPreset(id:String):JSONObject=locked {
        val list=brushPresets(full=true).getJSONArray("presets")
        (0 until list.length()).map {list.getJSONObject(it)}.firstOrNull {it.getString("id")==id}
            ?: error("笔刷预设不存在")
    }
    fun saveBrushPreset(p:JSONObject):JSONObject=locked {
        val id=p.optString("id",UUID.randomUUID().toString());validateId(id)
        val name=p.getString("name").trim();require(name.length in 1..64)
        val tool=p.getString("tool");val brush=ArtBrush.settings(tool,p.getJSONObject("brush"))
        ArtBrush.assetIds(brush).forEach { require(assetFile(it).isFile) {"笔刷资源不存在"} }
        val width=p.optDouble("width",6.0);val opacity=p.optDouble("opacity",1.0)
        require(width.isFinite() && width in 0.1..512.0 && opacity.isFinite() && opacity in 0.0..1.0)
        val item=JSONObject().put("id",id).put("name",name).put("tool",tool).put("brush",brush)
            .put("width",width).put("opacity",opacity).put("builtin",false)
        val old=readBrushList(brushPresetFile());val out=JSONArray()
        for(i in 0 until old.length())if(old.getJSONObject(i).getString("id")!=id)out.put(old.getJSONObject(i))
        require(out.length()<128) {"自定义预设最多128个"};out.put(item);atomic(brushPresetFile(),out.toString());item
    }
    fun deleteBrushPreset(id:String):JSONObject=locked {
        validateId(id);val old=readBrushList(brushPresetFile());val out=JSONArray();var found=false
        for(i in 0 until old.length()) { val item=old.getJSONObject(i)
            if(item.getString("id")==id)found=true else out.put(item) }
        require(found) {"自定义预设不存在"};atomic(brushPresetFile(),out.toString());JSONObject().put("deleted",id)
    }
    fun brushResources(kind:String?=null):JSONObject=locked {
        require(kind==null || kind in setOf("tip","texture"));val all=readBrushList(brushResourceFile());val out=JSONArray()
        for(i in 0 until all.length()) {val item=all.getJSONObject(i);if(kind==null || item.getString("kind")==kind)out.put(item)}
        JSONObject().put("resources",out)
    }
    fun importBrushResource(p:JSONObject):JSONObject=locked {
        val kind=p.getString("kind");require(kind in setOf("tip","texture"))
        val name=p.optString("name",if(kind=="tip")"图像笔尖" else "图像纹理").trim();require(name.length in 1..64)
        val encoded=p.getString("base64");require(encoded.length<=12*1024*1024)
        val bytes=Base64.decode(encoded,Base64.DEFAULT);require(bytes.isNotEmpty() && bytes.size<=8*1024*1024)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512) {"笔刷图像边长须为1–512像素"}
        val id=UUID.randomUUID().toString();val list=readBrushList(brushResourceFile());require(list.length()<128) {"笔刷图像最多128个"}
        ArtImagePolicy.requireBytes(bounds.outWidth.toLong()*bounds.outHeight*32+bytes.size,"导入笔刷图像")
        val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888}) ?: error("无法解码笔刷图像")
        try {atomicBytes(assetFile(id),ArtImagePolicy.encodePng(image,MAX_ASSET_BYTES))}
        finally {image.recycle()}
        val item=JSONObject().put("id",id).put("asset",id).put("name",name).put("kind",kind)
            .put("width",bounds.outWidth).put("height",bounds.outHeight)
        list.put(item);atomic(brushResourceFile(),list.toString());item
    }

    fun apply(actor: String, type: String, params: JSONObject): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
        if(type=="SELECTION_EDIT" && params.getString("action")=="MOVE") {
            val request=JSONObject(params.toString()).put("moveScope","selection").put("layerMode","current").put("unit","px")
            if(!request.has("dx"))request.put("dx",0.0)
            if(!request.has("dy"))request.put("dy",0.0)
            if(!request.has("documentId"))request.put("documentId",doc.getString("id"))
            if(!request.has("expectedRevision"))request.put("expectedRevision",doc.getJSONArray("operations").length())
            return@locked move(actor,request)
        }
        if (type=="SELECTION_TOOL" || type=="SELECTION_BEZIER" || type.startsWith("SHAPE_") || type.startsWith("REFERENCE_") || type.startsWith("ASSISTANT_") || type.startsWith("COLORIZE_") ||
            (type=="STROKE_ADD" && params.has("documentId")) || type == "VECTOR_LAYER_CREATE") {
            require(params.getString("documentId") == doc.getString("id")) { "工程已切换，请重新读取工程" }
            require(params.has("expectedRevision")) { "矢量操作必须绑定工程版本" }
        }
        if (params.has("expectedRevision")) {
            require(params.getInt("expectedRevision") == doc.getJSONArray("operations").length()) { "工程已被另一端修改，请刷新后重试" }
        }
        val operationId = UUID.randomUUID().toString()
        var normalized = JSONObject(params.toString())
        if(type=="STROKE_ADD") {
            val tool=normalized.optString("tool","pencil")
            if(tool in ArtFigure.tools || tool in ArtRasterPath.tools) {
                normalized=if(tool in ArtFigure.tools)ArtFigure.geometry(normalized.put("tool",tool)) else ArtRasterPath.normalize(normalized.put("tool",tool))
                ArtFigure.assetIds(normalized).forEach {id ->
                    val file=assetFile(id);require(file.isFile) {"图案图片资源不存在"}
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeFile(file.absolutePath,bounds)
                    require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512) {"图案图片边长须为1–512像素，请用brush.resource.import导入"}
                }
            }
            if((tool in ArtFigure.tools || tool in ArtRasterPath.tools) && normalized.getString("outline")!="brush") {
                require(!normalized.has("brush") && !normalized.has("brushPresetId")) {"仅当前笔刷描边使用brush/brushPresetId"}
            } else if(ArtBrush.supports(tool)) {
                normalized.put("tool",tool)
                if(tool=="mirror") {
                    val state=snapshot(doc).getJSONObject("state")
                    normalized=ArtMirror.normalize(normalized,state.getInt("width"),state.getInt("height"))
                }
                if(tool=="dyna")normalized=ArtDyna.normalize(normalized,snapshot(doc).getJSONObject("state"))
                if(tool=="line")normalized=ArtLine.geometry(normalized,snapshot(doc).getJSONObject("state"))
                val brushTool=ArtBrush.engineTool(normalized)
                val preset=if(normalized.has("brushPresetId"))brushPreset(normalized.getString("brushPresetId")) else null
                require(preset==null || preset.getString("tool")==brushTool) {"预设与当前工具不匹配"}
                val patch=if(normalized.has("brush"))normalized.getJSONObject("brush") else JSONObject()
                val config=ArtBrush.settings(brushTool,patch,preset?.getJSONObject("brush") ?: ArtBrush.defaults(brushTool))
                if(brushTool=="calligraphy" && normalized.has("nibAngle") && !patch.has("tip") && preset==null)
                    config.getJSONObject("tip").put("angle",normalized.getDouble("nibAngle"))
                ArtBrush.assetIds(config).forEach { require(assetFile(it).isFile) {"笔刷资源不存在"} }
                if(tool in ArtFigure.tools)normalized.put("points",ArtFigure.outlinePoints(normalized))
                if(tool in ArtRasterPath.tools)normalized.put("points",ArtRasterPath.outlinePoints(normalized))
                normalized=ArtBrush.prepare(normalized,config,normalized.optInt("brushSeed",java.util.Random().nextInt(Int.MAX_VALUE)))
            } else require(!normalized.has("brush") && !normalized.has("brushPresetId")) {"该工具不使用栅格笔刷引擎"}
        }
        if(type=="STROKE_ADD") {
            val state=snapshot(doc).getJSONObject("state")
            normalized.remove("selection");normalized.remove("selectionToLayer");normalized.remove("selectionCoveragePass")
            state.optJSONObject("selection")?.let {s ->
                val layer=ArtMenuOperations.layers(state).first {it.getString("id")==normalized.getString("layerId")}
                val inverse=android.graphics.Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse))
                normalized.put("selection",JSONObject(s.toString())).put("selectionToLayer",ArtShapes.encode(inverse))
            }
        }
        if(type=="STROKE_ADD"&&normalized.optString("tool")=="gradient") {
            normalized=ArtGradient.prepare(normalized,snapshot(doc).getJSONObject("state"))
        }
        if (type == "SELECTION_EDIT" && normalized.optString("action") == "COPY") {
            normalized.put("copyId", operationId)
        }
        val operation = JSONObject().put("id", operationId)
            .put("actor", actor).put("type", type).put("parameters", normalized)
            .put("timestamp", System.currentTimeMillis())
        doc.getJSONArray("operations").put(operation)
        // A failed replay or budget check must never overwrite the draft or its history.
        val result = snapshot(doc)
        requireRenderBudget(result)
        atomic(draft(doc.getString("id")), doc.toString())
        result.put("lastOperationId", operation.getString("id"))
    }

    private fun cropPlan(p: JSONObject): Pair<JSONObject, JSONObject> {
        val snap = current()
        if (p.has("documentId")) require(p.getString("documentId") == snap.getString("id")) { "工程已切换，请重新建立裁剪框" }
        if (p.has("expectedRevision")) require(p.getInt("expectedRevision") == snap.getInt("revision")) { "工程已更新，请重新建立裁剪框" }
        val state = snap.getJSONObject("state")
        val plan = ArtCrop.resolve(p, state.getInt("width"), state.getInt("height"))
            .put("documentId", snap.getString("id")).put("expectedRevision", snap.getInt("revision"))
        if (plan.getString("target") == "layer") {
            val id = p.optString("layerId", state.getString("selectedLayerId"))
            val layer = ArtMenuOperations.layers(state).firstOrNull { it.getString("id") == id } ?: error("裁剪图层不存在")
            require(!lockedByParent(layer, state.getJSONArray("layers"))) { "图层或父组已锁定" }
            require(layer.getString("kind") in setOf("paint","image","text","vector","group","colorize"))
            plan.put("layerId", id)
        }
        return snap to plan
    }
    fun cropGeometry(p: JSONObject, preview: Boolean = false): JSONObject = locked {
        val (snap, plan) = cropPlan(p)
        if (preview) ArtCropPreview.create(this,snap,plan,p.optInt("maxEdge",512))
        else JSONObject().put("plan",plan).put("operationApplied",false)
            .put("guideLines",JSONArray(ArtCrop.lines(ArtCrop.rect(plan),plan.getString("guides")).map {JSONArray(it.toList())}))
    }
    fun crop(actor: String, p: JSONObject): JSONObject = locked {
        require(actor in setOf("AWEI","LANER"))
        require(p.has("documentId") && p.has("expectedRevision")) {"确认裁剪须提供工程ID与预期版本"}
        val (snap, plan) = cropPlan(p)
        if (plan.getString("target") == "canvas") {
            ArtImagePolicy.requireDimensions(plan.getInt("width"),plan.getInt("height"))
            appendToCurrent(actor,"CROP",plan).put("cropResult",plan)
        } else {
            val state=snap.getJSONObject("state");val layer=ArtMenuOperations.layers(state).first {it.getString("id")==plan.getString("layerId")}
            val inverse=Matrix();require(ArtShapes.layerMatrix(state,layer).invert(inverse)) {"图层变换不可逆"}
            val rect=ArtCrop.rect(plan)
            val points=floatArrayOf(rect.x.toFloat(),rect.y.toFloat(),rect.right.toFloat(),rect.y.toFloat(),
                rect.right.toFloat(),rect.bottom.toFloat(),rect.x.toFloat(),rect.bottom.toFloat())
            inverse.mapPoints(points);require(points.all {it.isFinite() && kotlin.math.abs(it)<=100000000f}) {"裁剪边界的图层坐标过大"}
            var clip=(points.indices step 2).map {points[it].toDouble() to points[it+1].toDouble()}
            layer.optJSONArray("cropClip")?.let {old->
                val previous=(0 until old.length()).map {i->val q=old.getJSONArray(i);q.getDouble(0) to q.getDouble(1)}
                clip=ArtCrop.intersect(previous,clip)
            }
            appendToCurrent(actor,"LAYER_CROP",JSONObject(plan.toString()).put("clip",JSONArray(clip.map {JSONArray().put(it.first).put(it.second)})))
                .put("cropResult",plan.put("sourceRetained",true).put("clipVertices",clip.size))
        }
    }

    fun cropToSelection(actor: String): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val state = replay(loadCurrent())
        val selection = state.optJSONObject("selection") ?: error("请先创建选区")
        val width = state.getInt("width")
        val height = state.getInt("height")
        val left = kotlin.math.floor(selection.getDouble("x")).toInt().coerceIn(0, width)
        val top = kotlin.math.floor(selection.getDouble("y")).toInt().coerceIn(0, height)
        val right = kotlin.math.ceil(selection.getDouble("x") +
            selection.getDouble("width")).toInt().coerceIn(0, width)
        val bottom = kotlin.math.ceil(selection.getDouble("y") +
            selection.getDouble("height")).toInt().coerceIn(0, height)
        require(right > left && bottom > top) { "选区须与画布相交且包含至少一个像素" }
        ArtImagePolicy.requireDimensions(right - left, bottom - top)
        appendToCurrent(actor, "CROP", JSONObject().put("width", right - left)
            .put("height", bottom - top).put("x", left).put("y", top))
    }

    fun save(): JSONObject = locked { saveDocument(loadCurrent()) }

    // Save As changes the active document identity; the previous document stays available.
    fun saveAs(name: String, activate: Boolean = true, actor: String = "AWEI"): JSONObject = locked {
        val doc = copyCurrent(name, activate, actor)
        saveDocument(doc)
    }

    fun duplicate(name: String, actor: String = "AWEI"): JSONObject = locked {
        snapshot(copyCurrent(name, actor = actor))
    }

    fun linkExternal(id: String, uri: String): JSONObject = locked {
        validateId(id)
        require(draft(id).isFile && uri.startsWith("content://")) { "无效的外部工程地址" }
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        links.put(id, JSONObject().put("uri", uri).put("pending", false))
        atomic(externalLinks, links.toString())
        JSONObject().put("id", id).put("uri", uri)
    }

    fun markExternalSynced(id: String): JSONObject = locked {
        validateId(id)
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        val link = requireNotNull(links.optJSONObject(id)) { "工程没有外部保存位置" }
        link.put("pending", false)
        atomic(externalLinks, links.toString())
        JSONObject().put("id", id).put("uri", link.getString("uri"))
    }

    private fun externalLink(id: String): JSONObject? {
        if (!externalLinks.isFile) return null
        return JSONObject(externalLinks.readText()).optJSONObject(id)
    }
    fun recent(): JSONArray = locked {
        val ids = if (recentIndex.isFile) JSONArray(recentIndex.readText()) else JSONArray()
        val result = JSONArray()
        for (i in 0 until ids.length()) {
            val item = runCatching {
                val id = ids.getString(i)
                val file = draft(id)
                if (!file.isFile) null
                else {
                    val state = replay(JSONObject(file.readText()))
                    JSONObject().put("id", id)
                        .put("name", state.optString("name", "未命名工程"))
                        .put("width", state.getInt("width"))
                        .put("height", state.getInt("height"))
                }
            }.getOrNull()
            if (item != null) result.put(item)
        }
        result
    }


    fun sessions(): JSONArray = locked {
        if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
    }

    fun saveSession(name: String): JSONObject = locked {
        require(name.trim().isNotBlank()) { "会话名称不能为空" }
        val id = loadCurrent().getString("id")
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val out = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.getString("name") != name.trim()) out.put(item)
        }
        val entry = JSONObject().put("name", name.trim().take(100)).put("documentId", id)
        out.put(entry)
        atomic(sessionIndex, out.toString())
        entry
    }

    fun openSession(name: String): JSONObject = locked {
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val item = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name } ?: error("会话不存在")
        val id = item.getString("documentId")
        require(draft(id).isFile) { "会话中的工程已不存在" }
        val result = snapshot(JSONObject(draft(id).readText()))
        atomic(pointer, id)
        markRecent(id)
        result
    }

    fun deleteSession(name: String): JSONObject = locked {
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val out = JSONArray()
        var removed = false
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.getString("name") == name) removed = true else out.put(item)
        }
        require(removed) { "会话不存在" }
        atomic(sessionIndex, out.toString())
        JSONObject().put("name", name).put("deleted", true)
    }

    fun close(): JSONObject = locked {
        val id = loadCurrent().getString("id")
        require(pointer.delete()) { "无法关闭工程" }
        JSONObject().put("closedId", id)
    }

    fun discardCurrent(): JSONObject = locked {
        val doc = loadCurrent()
        val id = doc.getString("id")
        require(externalLink(id)?.optBoolean("pending") != true) {
            "外部工程尚未同步，请先保存到原文件"
        }
        val saved = archive(id)
        if (saved.isFile) {
            ZipFile(saved).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json"))
                val restored = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                atomic(draft(id), restored)
                atomic(File(documents, id + ".sha256"), digest(restored))
            }
        } else {
            require(draft(id).delete()) { "无法舍弃未保存的工程" }
        }
        require(pointer.delete()) { "无法关闭工程" }
        JSONObject().put("closedId", id).put("discarded", true)
    }

    fun saveIncrementalVersion(actor: String = "AWEI"): JSONObject = locked {
        val doc = loadCurrent()
        val baseName = replay(doc).getString("name").replace(Regex("_v[0-9]{3,}$"), "")
        val names = drafts.listFiles()?.mapNotNull { file ->
            runCatching { replay(JSONObject(file.readText())).getString("name") }.getOrNull()
        }?.toSet() ?: emptySet()
        var number = 1
        while (baseName + "_v" + number.toString().padStart(3, '0') in names) number++
        saveDocument(copyCurrent(baseName + "_v" + number.toString().padStart(3, '0'),
            actor = actor))
    }

    fun saveIncrementalBackup(): JSONObject = locked {
        val doc = loadCurrent()
        val id = doc.getString("id")
        val saved = archive(id)
        val hadSavedVersion = saved.isFile
        var number = 1
        val backupDirectory = saveDirectories.outputDirectory("backups")
        var backup = File(backupDirectory, id + "_b" + number.toString().padStart(3, '0') + ".ailart")
        while (backup.exists()) {
            number++
            backup = File(backupDirectory, id + "_b" + number.toString().padStart(3, '0') + ".ailart")
        }
        if (hadSavedVersion) {
            saveDirectories.prepare(backupDirectory)
            atomicBytes(backup, saved.readBytes())
        }
        val result = saveDocument(doc)
        result.put("backupPath", if (hadSavedVersion) backup.absolutePath else JSONObject.NULL)
    }

    fun createTemplate(name: String): JSONObject = locked {
        require(name.trim().isNotBlank()) { "模板名称不能为空" }
        val doc = JSONObject(loadCurrent().toString())
        val id = UUID.randomUUID().toString()
        doc.put("id", id)
        doc.put("base", replay(doc).put("name", name.trim().take(100)))
        doc.put("operations", JSONArray())
        val target = File(templates, id + ".ailart")
        writeArchive(doc, target)
        JSONObject().put("id", id).put("name", name.trim().take(100))
    }

    fun templates(): JSONArray = locked {
        val result = JSONArray()
        templates.listFiles()?.filter { it.extension == "ailart" }?.sortedBy { it.name }?.forEach { file ->
            ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json"))
                val doc = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                result.put(JSONObject().put("id", doc.getString("id"))
                    .put("name", doc.getJSONObject("base").getString("name")))
            }
        }
        result
    }

    fun fromTemplate(id: String, actor: String = "AWEI"): JSONObject = locked {
        validateId(id)
        val source = File(templates, id + ".ailart")
        require(source.isFile) { "模板不存在" }
        val doc = ZipFile(source).use { zip ->
            val entry = requireNotNull(zip.getEntry("project.json"))
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }
        doc.put("id", UUID.randomUUID().toString()).put("createdBy", actor)
        val newId = doc.getString("id")
        val result = snapshot(doc)
        atomic(draft(newId), doc.toString())
        atomic(pointer, newId)
        markRecent(newId)
        result
    }

    private fun copyCurrent(name: String, activate: Boolean = true,
                            actor: String = "AWEI"): JSONObject {
        require(name.trim().isNotBlank()) { "工程名称不能为空" }
        val source = loadCurrent()
        val doc = JSONObject(source.toString())
        val id = UUID.randomUUID().toString()
        doc.put("id", id).put("createdBy", actor)
        // A new document starts with the current composed state so old operations remain immutable.
        doc.put("base", replay(source).put("name", name.trim().take(100)))
        doc.put("operations", JSONArray())
        snapshot(doc)
        atomic(draft(id), doc.toString())
        if (activate) {
            atomic(pointer, id)
            markRecent(id)
        }
        return doc
    }

    private fun markRecent(id: String) {
        val existing = if (recentIndex.isFile) JSONArray(recentIndex.readText()) else JSONArray()
        val entries = JSONArray().put(id)
        for (i in 0 until existing.length()) {
            val next = existing.getString(i)
            if (next != id && entries.length() < 16) entries.put(next)
        }
        atomic(recentIndex, entries.toString())
    }

    private fun saveDocument(doc: JSONObject): JSONObject {
        val id = doc.getString("id")
        val destination = archive(id)
        saveDirectories.prepare(requireNotNull(destination.parentFile))
        writeArchive(doc, destination)
        saveDirectories.rememberProject(id, destination)
        atomic(File(documents, id + ".sha256"), digest(doc.toString()))
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        links.optJSONObject(id)?.let {
            it.put("pending", true)
            atomic(externalLinks, links.toString())
        }
        return JSONObject().put("id", id).put("path", destination.absolutePath)
            .put("externalUri", links.optJSONObject(id)?.optString("uri", "") ?: "")
            .put("bytes", destination.length())
    }

    private fun writeArchive(doc: JSONObject, destination: File) {
        val temp = File(destination.parentFile, "." + UUID.randomUUID() + ".tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(doc.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val used = mutableSetOf<String>()
                // Include assets in base layers and inactive history, not only the visible state.
                ArtMenuOperations.assets(doc) { node, key -> used.add(node.getString(key)) }
                used.forEach { asset ->
                    zip.putNextEntry(ZipEntry("assets/" + asset + ".png"))
                    zip.write(assetFile(asset).readBytes())
                    zip.closeEntry()
                }
            }
            require(temp.renameTo(destination)) { "保存工程文件失败" }
        } finally { temp.delete() }
    }

    private fun digest(data: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun open(id: String): JSONObject = locked {
        validateId(id)
        if (!draft(id).exists()) {
            val file = archive(id)
            require(file.isFile) { "工程不存在" }
            ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json")) { "工程缺少 project.json" }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(32 * 1024 * 1024 + 1) }
                require(bytes.size <= 32 * 1024 * 1024) { "工程数据过大" }
                val doc = JSONObject(String(bytes, Charsets.UTF_8))
                require(doc.getString("id") == id && doc.getInt("format") == 1)
                snapshot(doc)
                zip.entries().asSequence().filter { it.name.startsWith("assets/") }.forEach { asset ->
                    val name = asset.name.removePrefix("assets/").removeSuffix(".png")
                    require(asset.name == "assets/$name.png")
                    validateId(name)
                    require(asset.size in 0..MAX_ASSET_BYTES.toLong()) { "工程图片资源大小无效" }
                    ArtImagePolicy.requireBytes(asset.size * 2, "读取工程图片资源")
                    val content = zip.getInputStream(asset).use { it.readNBytes(MAX_ASSET_BYTES + 1) }
                    require(content.size <= MAX_ASSET_BYTES)
                    atomicBytes(assetFile(name), content)
                }
                atomic(draft(id), doc.toString())
            }
        }
        val doc = JSONObject(draft(id).readText())
        val result = snapshot(doc)
        requireRenderBudget(result)
        atomic(pointer, id)
        markRecent(id)
        result
    }

    fun importArchive(bytes: ByteArray, actor: String = "AWEI"): JSONObject = locked {
        require(bytes.size in 1..64 * 1024 * 1024) { "工程文件超过 64 MB" }
        var project: JSONObject? = null
        val importedAssets = mutableMapOf<String, ByteArray>()
        var importedAssetBytes = 0L
        val archiveAssetBudget = minOf(128L * 1024 * 1024, ArtImagePolicy.budgetBytes() / 3)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 128) { "工程条目过多" }
                val path = entry.name
                when {
                    path == "project.json" -> {
                        require(project == null) { "工程数据重复" }
                        val data = zip.readNBytes(32 * 1024 * 1024 + 1)
                        require(data.size <= 32 * 1024 * 1024)
                        project = JSONObject(String(data, Charsets.UTF_8))
                    }
                    path.startsWith("assets/") -> {
                        val asset = path.removePrefix("assets/").removeSuffix(".png")
                        require(path == "assets/$asset.png") { "工程资源路径无效" }
                        validateId(asset)
                        require(asset !in importedAssets) { "工程资源重复" }
                        val available = minOf(MAX_ASSET_BYTES.toLong(), archiveAssetBudget - importedAssetBytes)
                            .coerceAtLeast(0).toInt()
                        val data = zip.readNBytes(available + 1)
                        require(data.size <= available) { "工程图片资源超过当前读取预算" }
                        importedAssetBytes += data.size
                        importedAssets[asset] = data
                    }
                    else -> error("工程中含有未知条目")
                }
                zip.closeEntry()
            }
        }
        val doc = requireNotNull(project) { "工程缺少 project.json" }
        require(doc.getInt("format") == 1) { "工程格式不受支持" }
        validateId(doc.getString("id"))
        val operations = doc.getJSONArray("operations")
        val assetIds = importedAssets.keys.associateWith { UUID.randomUUID().toString() }
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            if (op.getString("type") == "IMAGE_IMPORT") {
                val parameters = op.getJSONObject("parameters")
                if (!parameters.has("id")) parameters.put("id", parameters.getString("asset"))
            }
        }
        ArtMenuOperations.assets(doc) { node, key ->
            val old = node.getString(key)
            require(old in importedAssets) { "工程图片资源缺失：$old" }
            node.put(key, assetIds.getValue(old))
        }
        val id = UUID.randomUUID().toString()
        doc.put("id", id).put("createdBy", actor)
        val result = snapshot(doc)
        importedAssets.forEach { (asset, data) -> atomicBytes(assetFile(assetIds.getValue(asset)), data) }
        requireRenderBudget(result)
        atomic(draft(id), doc.toString())
        atomic(pointer, id)
        markRecent(id)
        result
    }

    private fun imageBytes(encoded: String): ByteArray {
        require(encoded.length <= MAX_IMAGE_INPUT_BYTES * 2) { "图片大小上限为 8 MB" }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 1..MAX_IMAGE_INPUT_BYTES) { "图片大小上限为 8 MB" }
        return bytes
    }

    fun openImage(encoded: String, name: String = "未命名图像",
                  actor: String = "AWEI", confirmResize: JSONObject? = null): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        // Preflight throws a confirmation request before creating IDs, files or changing the pointer.
        val (bitmap, metadata) = ArtImagePolicy.decode(imageBytes(encoded), confirmResize)
        val width = bitmap.width; val height = bitmap.height
        val png = try { ArtImagePolicy.encodePng(bitmap, MAX_ASSET_BYTES) } finally { bitmap.recycle() }
        val id = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val layer = newLayer(UUID.randomUUID().toString(), "image", "图像图层", "", asset)
        val base = JSONObject().put("width", width).put("height", height)
            .put("name", name.trim().ifBlank { "未命名图像" }.take(100))
            .put("background", "#00000000").put("layers", JSONArray().put(layer))
            .put("selectedLayerId", layer.getString("id")).put("selection", JSONObject.NULL)
        val doc = JSONObject().put("format", 1).put("id", id)
            .put("base", base).put("createdBy", actor).put("operations", JSONArray())
        atomicBytes(assetFile(asset), png)
        try {
            val result = snapshot(doc)
            requireRenderBudget(result)
            atomic(draft(id), doc.toString())
            atomic(pointer, id)
            markRecent(id)
            result.put("imageImport", metadata)
        } catch (error: Throwable) {
            assetFile(asset).delete()
            throw error
        }
    }

    fun importImage(actor: String, encoded: String, confirmResize: JSONObject? = null): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val state = replay(loadCurrent())
        val (bitmap, metadata) = ArtImagePolicy.decode(imageBytes(encoded), confirmResize,
            ArtImagePolicy.renderBytes(this, state, state.getInt("width"), state.getInt("height")))
        val width = bitmap.width; val height = bitmap.height
        val id = UUID.randomUUID().toString()
        val png = try { ArtImagePolicy.encodePng(bitmap, MAX_ASSET_BYTES) } finally { bitmap.recycle() }
        atomicBytes(assetFile(id), png)
        val doc = loadCurrent()
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("type", "IMAGE_IMPORT").put("timestamp", System.currentTimeMillis())
            .put("parameters", JSONObject().put("asset", id).put("width", width).put("height", height))
        doc.getJSONArray("operations").put(op)
        try {
            val result = snapshot(doc)
            requireRenderBudget(result)
            atomic(draft(doc.getString("id")), doc.toString())
            result.put("imageImport", metadata)
        } catch (error: Throwable) {
            assetFile(id).delete()
            throw error
        }
    }

    fun textSource(p: JSONObject): JSONObject = locked {
        val snapshot=current();require(p.getString("documentId")==snapshot.getString("id"))
        val state=snapshot.getJSONObject("state");val layers=state.getJSONArray("layers")
        val layer=(0 until layers.length()).map {layers.getJSONObject(it)}.firstOrNull {it.getString("id")==p.getString("id")} ?: error("文字图层不存在")
        require(layer.getString("kind")=="text")
        val anchor=ArtText.anchor(layer)
        JSONObject().put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
            .put("id",layer.getString("id")).put("text",JSONObject(layer.getJSONObject("text").toString()))
            .put("x",anchor.first).put("y",anchor.second).put("scale",layer.getDouble("scale")).put("rotation",layer.getDouble("rotation"))
    }
    fun textGeometry(p: JSONObject): JSONObject = locked {
        val snapshot=current();require(p.getString("documentId")==snapshot.getString("id"))
        val state=snapshot.getJSONObject("state");val layers=state.getJSONArray("layers")
        val choices=JSONArray()
        for(i in 0 until layers.length()) {
            val layer=layers.getJSONObject(i)
            if(layer.getString("kind")!="vector"||!ArtShapes.visible(state,layer))continue
            for(shape in ArtShapes.items(layer)) if(shape.getBoolean("visible")) {
                val frozen=JSONObject(shape.toString());val matrix=ArtShapes.matrix(shape.getJSONArray("matrix"))
                matrix.postConcat(layerMatrix(layer,layers));frozen.put("matrix",ArtShapes.encode(matrix))
                choices.put(JSONObject().put("layerId",layer.getString("id")).put("id",shape.getString("id"))
                    .put("label",layer.getString("name")+" · "+shape.getString("kind")+" · "+shape.getString("id").take(8))
                    .put("shape",frozen).put("canFill",ArtShapes.canFill(shape)))
            }
        }
        JSONObject().put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
            .put("geometries",choices).put("coordinates","frozen document coordinates; create text at x=0,y=0")
    }

    /** Text creation and updates share the same locked source/cache transaction on both clients. */
    fun writeText(actor: String, p: JSONObject, update: Boolean): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
        require(p.getString("documentId") == doc.getString("id") &&
            p.getInt("expectedRevision") == doc.getJSONArray("operations").length()) {
            "工程已切换或被另一端修改，请刷新后重新编辑文字"
        }
        val state = replay(doc)
        val layers = state.getJSONArray("layers")
        val id = if (update) p.getString("id") else UUID.randomUUID().toString()
        val previous = if (update) (0 until layers.length()).map { layers.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: error("文字图层不存在") else null
        if (previous != null) require(previous.getString("kind") == "text" &&
            !lockedByParent(previous, layers)) { "请选择未锁定的文字图层" }
        val source = previous?.optJSONObject("text")?.let { JSONObject(it.toString()) } ?: JSONObject()
        p.keys().forEach { source.put(it, p.get(it)) }
        if(p.optBoolean("clearGeometry")) { source.remove("textPath");source.remove("shapeInside") }
        if(p.has("textPath")) { source.remove("shapeInside");source.put("textPath",p.getJSONObject("textPath")) }
        if(p.has("shapeInside")) { source.remove("textPath");source.put("shapeInside",p.getJSONObject("shapeInside")) }
        if(p.has("content")&&!p.has("svgSource")&&source.optString("sourceMode")=="svg")
            require(p.has("sourceMode")&&p.getString("sourceMode")!="svg") {"SVG 文字请更新 svgSource，或明确 sourceMode=plain/rich 转换正文"}
        if(p.has("sourceMode")&&p.getString("sourceMode")=="plain")source.put("spans",JSONArray())
        val text = ArtText.prepare(source)
        val anchor = previous?.let { ArtText.anchor(it) } ?: (0.0 to 0.0)
        val x = if (p.has("x")) p.getDouble("x") else anchor.first
        val y = if (p.has("y")) p.getDouble("y") else anchor.second
        require(x.isFinite() && y.isFinite()) { "文字位置无效" }
        ArtTextShaper.load(root)
        val bitmap = ArtText.render(text, ArtImagePolicy.renderBytes(this, state,
            state.getInt("width"), state.getInt("height")))
        val asset = UUID.randomUUID().toString()
        val bytes = try { ArtImagePolicy.encodePng(bitmap, MAX_ASSET_BYTES) } finally { bitmap.recycle() }
        atomicBytes(assetFile(asset), bytes)
        try {
            val result = appendToCurrent(actor, if (update) "TEXT_UPDATE" else "TEXT_CREATE",
                JSONObject().put("id", id).put("asset", asset).put("text", text).also { parameters ->
                    val offset=floatArrayOf(text.getDouble("cacheOriginX").toFloat(),text.getDouble("cacheOriginY").toFloat())
                    if(previous!=null)ArtShapes.localMatrix(previous).mapVectors(offset)
                    parameters.put("x",x+offset[0]).put("y",y+offset[1])
                })
            result.put("textLayerId", id).put("textNotice", ArtText.NOTICE)
        } catch (error: Throwable) { assetFile(asset).delete(); throw error }
    }

    private fun requireRenderBudget(snapshot: JSONObject) {
        val state = snapshot.getJSONObject("state")
        ArtImagePolicy.requireDimensions(state.getInt("width"), state.getInt("height"))
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this, state,
            state.getInt("width"), state.getInt("height"))+ArtBrush.renderOverhead(state,state.getInt("width"),state.getInt("height"))+
            ArtReferences.items(state).size*ArtReferences.PREVIEW_EDGE.toLong()*ArtReferences.PREVIEW_EDGE*8, "画布合成与参考预览")
    }

    private fun snapshot(doc: JSONObject): JSONObject {
        val state = replay(doc)
        val id = doc.getString("id")
        val saved = archive(id)
        val operations = doc.getJSONArray("operations")
        val (undoStack, redoStack) = historyStacks(operations)
        val operationById = (0 until operations.length())
            .map { operations.getJSONObject(it) }
            .filter { it.getString("type") !in setOf("REVERT", "RESTORE") }
            .associateBy { it.getString("id") }
        fun operationLabel(id: String?): String {
            if (id == null) return ""
            val operation = operationById[id] ?: return ""
            return when (operation.getString("type")) {
                "MENU_LAYER_CHANGE" -> operation.getJSONObject("parameters").getString("label")
                "LAYER_CREATE", "IMAGE_IMPORT", "PASTE_IMAGE" -> "添加图层"
                "ASSISTANT_CREATE" -> "添加辅助尺规"
                "ASSISTANT_SELECT" -> "选择辅助尺规"
                "ASSISTANT_UPDATE" -> "编辑辅助尺规"
                "ASSISTANT_DELETE" -> "删除辅助尺规"
                "ASSISTANT_SETTINGS" -> "辅助尺规设置"
                "REFERENCE_SHOW" -> "参考图像整体显隐"
                "REFERENCE_ADD" -> "添加参考图像"
                "REFERENCE_BATCH_ADD" -> "导入参考集合"
                "REFERENCE_REPLACE" -> "刷新外部参考"
                "REFERENCE_EMBED" -> "参考图转为内嵌"
                "REFERENCE_SELECT" -> "选择参考图像"
                "REFERENCE_TRANSFORM" -> "变换参考图像"
                "REFERENCE_STYLE" -> "参考图像样式"
                "REFERENCE_DELETE" -> "删除参考图像"
                "VECTOR_LAYER_CREATE" -> "添加矢量图层"
                "SHAPE_COMIC_CUT" -> "漫画分格切分"
                "SHAPE_COMIC_MERGE" -> "漫画分格合并"
                "SHAPE_FREEHAND" -> "绘制或接续矢量徒手路径"
                "SHAPE_CREATE" -> "添加矢量形状"
                "SHAPE_SELECT" -> "选择形状"
                "SHAPE_ALIGN" -> "对齐形状"
                "SHAPE_DISTRIBUTE" -> "分布形状"
                "SHAPE_SHEAR" -> "剪切形状"
                "SHAPE_TRANSFORM" -> "变换形状"
                "SHAPE_PATH_EDIT" -> "编辑路径节点"
                "SHAPE_PATH_TOPOLOGY" -> "断开或连接子路径"
                "SHAPE_PATH_CONVERT" -> "形状转路径"
                "SHAPE_PATH_COMBINE" -> "合成子路径对象"
                "SHAPE_DELETE" -> "删除形状"
                "SHAPE_STYLE" -> "形状样式"
                "TEXT_CREATE" -> "添加文字"
                "TEXT_UPDATE" -> "编辑文字"
                "GROUP_CREATE" -> "新建图层组"
                "LAYER_DELETE" -> "删除图层"
                "STROKE_ADD" -> when (operation.getJSONObject("parameters").optString("tool")) {
                    "gradient" -> ArtGradient.modes.getValue(operation.getJSONObject("parameters").optString("gradientMode","linear"))+"渐变"
                    "mirror" -> "多重画笔"
                    "dyna" -> "动态画笔"
                    "calligraphy" -> "斜头书法笔"
                    "line" -> "直线"
                    "rectangle" -> "矩形"
                    "ellipse" -> "椭圆"
                    "polygon" -> "多边形"
                    "polyline" -> "折线"
                    "bezier" -> "贝塞尔曲线"
                    "ink" -> "自由画笔"
                    "pencil" -> "铅笔"
                    "soft" -> "软笔"
                    "spray" -> "喷枪"
                    "eraser" -> "橡皮擦"
                    else -> "绘制笔画"
                }
                "STROKE_ERASE" -> "删除笔画"
                "TRANSFORM_AFFINE" -> "自由变换图层"
                "TRANSFORM_PIXELS" -> "变形图层或选区像素"
                "MOVE_LAYER" -> "移动图层"
                "MOVE_PIXELS" -> "移动选区像素"
                "TRANSFORM" -> "变换图层"
                "CROP" -> "裁剪画布"
                "LAYER_CROP" -> "裁剪图层边界"
                "CANVAS_RESIZE" -> "更改画布大小"
                "IMAGE_BACKGROUND" -> "更改图像背景色与透明度"
                "LAYER_RENAME" -> "重命名图层"
                "LAYER_SELECT" -> "选择图层"
                "LAYER_VISIBLE" -> "显示或隐藏图层"
                "LAYER_LOCK" -> "锁定或解锁图层"
                "LAYER_OPACITY" -> "调整图层不透明度"
                "LAYER_BLEND" -> "调整图层混合模式"
                "LAYER_PROPERTIES" -> "修改图层属性"
                "LAYER_MOVE", "LAYER_MOVE_STEP" -> "调整图层顺序"
                "COLORIZE_CREATE" -> "新建上色蒙版"
                "COLORIZE_STROKE" -> "颜色线索"
                "COLORIZE_REMOVE_STROKE","COLORIZE_CLEAR" -> "清理颜色线索"
                "COLORIZE_PALETTE" -> "颜色线索调色板"
                "COLORIZE_SETTINGS" -> "上色蒙版参数"
                "COLORIZE_OUTPUT" -> "更新填色结果"
                "COLORIZE_CONVERT" -> "蒙版转为绘画图层"
                "PIXEL_REPAIR" -> "智能修补"
                "PIXEL_EDIT" -> if (operation.getJSONObject("parameters").optString("mode") == "CLEAR")
                    "清除像素" else "填充像素"
                "PIXEL_PASTE" -> if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS") "填充相连区域" else if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS_ERASE") "擦除相连区域" else when(operation.getJSONObject("parameters").optString("action")) {
                    "ENCLOSE_FILL" -> "围合填充";"ENCLOSE_ERASE" -> "围合擦除";else -> "粘贴像素"
                }
                "LAYER_COPY" -> "复制图层"
                "SELECTION_TOOL" -> when(operation.getJSONObject("parameters").getString("tool")) {"contiguous"->"连续区域选区";"similar"->"相似色选区";"magnetic"->"磁性套索选区";"adjust"->"调整软选区";else->"创建基本选区"}
                "SELECTION_BEZIER" -> "贝塞尔曲线选区"
                "SELECTION_CREATE", "SELECTION_CLEAR", "SELECTION_EDIT" -> "修改选区"
                "DOCUMENT_RENAME" -> "重命名工程"
                else -> "画室操作"
            }
        }
        // A state represents the first N currently reachable edits. Redone edits stay
        // visible as future states until a new edit starts a different branch.
        val reachableIds = undoStack + redoStack.asReversed()
        val originalOrder = operationById.keys.withIndex().associate { it.value to it.index }
        var newestOriginalIndex = -1
        val timeline = JSONArray().put(JSONObject().put("id", "")
            .put("label", "初始画布").put("actor", doc.getString("createdBy"))
            .put("timestamp", 0L))
        for (operationId in reachableIds) {
            val operation = operationById.getValue(operationId)
            val originalIndex = originalOrder.getValue(operationId)
            val reappliedOutOfOrder = originalIndex < newestOriginalIndex
            newestOriginalIndex = maxOf(newestOriginalIndex, originalIndex)
            val entry = JSONObject().put("id", operationId)
                .put("label", if (reappliedOutOfOrder)
                    "重新应用 · " + operationLabel(operationId) else operationLabel(operationId))
                .put("actor", operation.getString("actor"))
                .put("type", operation.getString("type"))
                .put("timestamp", operation.optLong("timestamp", 0L))
            if (operation.getString("type") == "STROKE_ADD") {
                val stroke = operation.getJSONObject("parameters")
                entry.put("tool", stroke.optString("tool", "ink"))
                    .put("color", stroke.getString("color"))
            }
            timeline.put(entry)
        }
        return JSONObject().put("id", doc.getString("id"))
            .put("state", state).put("revision", doc.getJSONArray("operations").length())
            .put("timeline", timeline).put("timelinePosition", undoStack.size)
            .put("canUndo", undoStack.isNotEmpty()).put("canRedo", redoStack.isNotEmpty())
            .put("undoLabel", operationLabel(undoStack.lastOrNull()))
            .put("redoLabel", operationLabel(redoStack.lastOrNull()))
            .put("hasClipboard", editClipboard.isFile)
            .put("dirty", externalLink(id)?.optBoolean("pending") == true ||
                !saved.exists() || File(documents, id + ".sha256").let { marker ->
                if (marker.isFile) marker.readText() != digest(doc.toString())
                else draft(id).lastModified() > saved.lastModified()
            })
            .put("storagePath", if (saved.isFile) saved.absolutePath else "")
            .put("externalUri", externalLink(id)?.optString("uri", "") ?: "")
            .put("externalPending", externalLink(id)?.optBoolean("pending") ?: false)
            .put("operations", JSONArray(doc.getJSONArray("operations").toString()))
    }

    private fun replay(doc: JSONObject): JSONObject {
        val state = JSONObject(doc.getJSONObject("base").toString())
        val operations = doc.getJSONArray("operations")
        val disabled = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val target = op.optJSONObject("parameters")?.optString("targetId") ?: ""
            when (op.getString("type")) {
                "REVERT" -> {
                    require(target in seen && target !in disabled) { "目标操作已撤销或不存在" }
                    disabled.add(target)
                }
                "RESTORE" -> {
                    require(target in disabled) { "目标操作不在撤销状态" }
                    disabled.remove(target)
                }
                else -> require(seen.add(op.getString("id"))) { "重复的操作 ID" }
            }
        }
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            if (op.getString("id") !in disabled && op.getString("type") !in setOf("REVERT", "RESTORE")) {
                edit(state, op.getString("type"), op.getJSONObject("parameters"))
            }
        }
        ArtImagePolicy.requireDimensions(state.getInt("width"), state.getInt("height"))
        ArtShapes.validateDocument(state)
        ArtReferences.validate(state)
        ArtAssistants.validate(state)
        ArtColorize.validate(state)
        state.optJSONObject("selection")?.let {ArtSelection.validate(it)}
        state.optJSONObject("previousSelection")?.let {ArtSelection.validate(it)}
        return state
    }

    private fun edit(state: JSONObject, type: String, p: JSONObject) {
        val layers = state.getJSONArray("layers")
        fun find(id: String): Pair<Int, JSONObject> {
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                if (layer.getString("id") == id) return i to layer
            }
            error("图层不存在：$id；该撤销与后续操作冲突")
        }
        when (type) {
            "ASSISTANT_CREATE","ASSISTANT_SELECT","ASSISTANT_UPDATE","ASSISTANT_DELETE","ASSISTANT_SETTINGS" -> ArtAssistants.edit(state,type,p)
            "REFERENCE_ADD","REFERENCE_BATCH_ADD","REFERENCE_REPLACE","REFERENCE_EMBED","REFERENCE_SELECT","REFERENCE_TRANSFORM","REFERENCE_STYLE","REFERENCE_DELETE","REFERENCE_SHOW" -> ArtReferences.edit(state,type,p)
            "COLORIZE_CREATE","COLORIZE_STROKE","COLORIZE_REMOVE_STROKE","COLORIZE_CLEAR",
            "COLORIZE_PALETTE","COLORIZE_SETTINGS","COLORIZE_OUTPUT","COLORIZE_CONVERT" -> ArtColorize.edit(state,type,p)
            "TEXT_CREATE", "TEXT_UPDATE" -> {
                val id = p.getString("id")
                validateId(id); validateId(p.getString("asset"))
                val text = p.getJSONObject("text")
                ArtText.normalize(text)
                require(text.getInt("cacheWidth") > 0 && text.getInt("cacheHeight") > 0)
                val layer = if (type == "TEXT_CREATE") {
                    require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                    newLayer(id, "text", "文字 · " + text.getString("content").lineSequence().first().take(24), "", "")
                        .also { layers.put(it) }
                } else find(id).second.also {
                    require(it.getString("kind") == "text" && !lockedByParent(it, layers)) { "文字图层已锁定或类型不符" }
                }
                for (key in listOf("x", "y")) require(p.getDouble(key).isFinite())
                layer.put("asset", p.getString("asset")).put("text", JSONObject(text.toString()))
                    .put("x", p.getDouble("x")).put("y", p.getDouble("y"))
                state.put("selectedLayerId", id)
            }
            "SHAPE_COMIC_CUT", "SHAPE_COMIC_MERGE" -> ArtComicPanels.apply(state,p)
            "SHAPE_ALIGN", "SHAPE_DISTRIBUTE", "SHAPE_SHEAR", "SHAPE_FREEHAND", "SHAPE_CREATE", "SHAPE_SELECT", "SHAPE_TRANSFORM", "SHAPE_DELETE", "SHAPE_STYLE", "SHAPE_PATH_EDIT", "SHAPE_PATH_TOPOLOGY", "SHAPE_PATH_CONVERT", "SHAPE_PATH_COMBINE" ->
                ArtShapes.edit(state, type, p)
            "VECTOR_LAYER_CREATE" -> {
                val id = p.getString("id"); validateId(id)
                require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                val parent = p.optString("parentId")
                if (parent.isNotBlank()) require(find(parent).second.getString("kind") == "group")
                val name = p.optString("name", "矢量图层").trim()
                require(name.isNotBlank() && name.length <= 100)
                layers.put(newLayer(id, "vector", name, parent, "").put("shapes", JSONArray()))
                if (p.optBoolean("select", true)) state.put("selectedLayerId", id)
                state.put("shapeSelection", JSONObject.NULL)
            }
            "MENU_LAYER_CHANGE" -> ArtMenuOperations.edit(state, p)
            "LAYER_CREATE", "GROUP_CREATE", "IMAGE_IMPORT" -> {
                val id = p.optString("id").ifBlank { p.optString("asset") }
                require(id.isNotBlank())
                require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                val parentId = p.optString("parentId")
                if (parentId.isNotBlank()) require(find(parentId).second.getString("kind") == "group")
                val kind = if (type == "GROUP_CREATE") "group" else if (type == "IMAGE_IMPORT") "image" else "paint"
                layers.put(newLayer(id, kind, p.optString("name", if (kind == "group") "图层组" else "图层"), parentId, p.optString("asset")))
                if (p.optBoolean("select", false)) state.put("selectedLayerId", id)
            }
            "PASTE_IMAGE" -> {
                val asset = p.getString("asset")
                validateId(asset)
                val id = p.getString("id")
                validateId(id)
                require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                val layer = newLayer(id, "image", "粘贴图层", "", asset)
                layer.put("x", p.getInt("x")).put("y", p.getInt("y"))
                layers.put(layer)
                state.put("selectedLayerId", id)
            }
            "PIXEL_REPAIR" -> {
                val layer=find(p.getString("layerId")).second
                require(layer.getString("kind") in setOf("paint","image") && !lockedByParent(layer,layers))
                require(layer.optString("parentId").isBlank() &&
                    layer.getDouble("x")==0.0 && layer.getDouble("y")==0.0 &&
                    layer.getDouble("scale")==1.0 && layer.getDouble("rotation")==0.0 && !layer.has("affine"))
                val x=p.getInt("x");val y=p.getInt("y");val w=p.getInt("width");val h=p.getInt("height")
                require(x>=0 && y>=0 && w>0 && h>0 && x.toLong()+w<=state.getInt("width") &&
                    y.toLong()+h<=state.getInt("height") && w.toLong()*h<=ArtSmartPatch.MAX_REGION_PIXELS)
                val order=contentOrder(layer)
                for(key in listOf("erase","patch")) {
                    val asset=p.getJSONObject(key).getString("asset");validateId(asset)
                    order.put(JSONObject().put("kind",if(key=="erase")"erase" else "paste")
                        .put("asset",asset).put("x",x).put("y",y))
                }
            }
            "PIXEL_EDIT", "PIXEL_PASTE" -> {
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") in setOf("paint", "image") && !lockedByParent(layer, layers))
                require(layer.optString("parentId").isBlank() &&
                    layer.getDouble("x") == 0.0 && layer.getDouble("y") == 0.0 &&
                    layer.getDouble("scale") == 1.0 && layer.getDouble("rotation") == 0.0 && !layer.has("affine")) {
                    "请先取消图层变换和分组，再编辑选区像素"
                }
                val order = contentOrder(layer)
                if (type == "PIXEL_PASTE") {
                    validateId(p.getString("asset"))
                    if(p.optString("action") in setOf("ENCLOSE_FILL","ENCLOSE_ERASE") || p.optString("algorithm")=="shared-soft-multiseed-fill-v1") {
                        val x=p.getInt("x");val y=p.getInt("y");val w=p.getInt("width");val h=p.getInt("height")
                        require(x>=0 && y>=0 && w>0 && h>0 && x.toLong()+w<=state.getInt("width") &&
                            y.toLong()+h<=state.getInt("height") && w.toLong()*h<=ArtEncloseFill.MAX_PIXELS)
                    }
                    val kind = if (p.optString("action") in setOf("FILL_CONTIGUOUS_ERASE","ENCLOSE_ERASE")) "erase" else "paste"
                    val blend=ArtPixelBlend.validate(p.optString("blend","normal"))
                    require(kind!="erase" || blend=="normal") {"擦除记录不能使用颜色混合模式"}
                    order.put(JSONObject().put("kind", kind).put("asset", p.getString("asset"))
                        .put("blend",blend).put("x", p.getInt("x")).put("y", p.getInt("y")))
                } else {
                    val x = p.getInt("x"); val y = p.getInt("y")
                    val width = p.getInt("width"); val height = p.getInt("height")
                    require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
                        x.toLong() + width <= state.getInt("width") &&
                        y.toLong() + height <= state.getInt("height"))
                    val mode = p.getString("mode")
                    require(mode == "CLEAR" || mode == "FILL")
                    if (mode == "FILL") requireColor(p.getString("color"))
                    val event = JSONObject().put("kind", mode.lowercase()).put("x", x).put("y", y)
                        .put("width", width).put("height", height)
                        .put("color", p.optString("color"))
                    p.optJSONObject("selection")?.let { event.put("selection", JSONObject(it.toString())) }
                    order.put(event)
                }
            }
            "DOCUMENT_RENAME" -> state.put("name", p.getString("name").trim().take(100).also { require(it.isNotBlank()) })
            "IMAGE_BACKGROUND" -> state.put("background",
                p.getString("color").also { requireColor(it) })
            "LAYER_SELECT" -> state.put("selectedLayerId", find(p.getString("id")).second.getString("id"))
            "LAYER_RENAME" -> find(p.getString("id")).second.put("name", p.getString("name").take(100))
            "LAYER_VISIBLE" -> find(p.getString("id")).second.put("visible", p.getBoolean("visible"))
            "LAYER_OPACITY" -> find(p.getString("id")).second.put("opacity", p.getDouble("opacity").also { require(it in 0.0..1.0) })
            "LAYER_LOCK" -> find(p.getString("id")).second.put("locked", p.getBoolean("locked"))
            "LAYER_BLEND" -> find(p.getString("id")).second.put("blend", p.getString("blend").also { require(it in setOf("normal", "multiply", "screen", "add")) })
            "LAYER_PROPERTIES" -> {
                val layer = find(p.getString("id")).second
                if(p.has("colorLabel")) {
                    val label=ArtLayerLabels.value(JSONObject().put("colorLabel",p.get("colorLabel")))
                    layer.put("colorLabel",label)
                }
                val name = p.optString("name", layer.getString("name")).trim().take(100)
                require(name.isNotBlank()) { "图层名称不能为空" }
                val opacity = p.optDouble("opacity", layer.getDouble("opacity"))
                require(opacity in 0.0..1.0) { "图层不透明度必须在 0–100% 之间" }
                val blend = p.optString("blend", layer.getString("blend"))
                require(blend in setOf("normal", "multiply", "screen", "add")) { "不支持的混合模式" }
                layer.put("name", name).put("opacity", opacity).put("blend", blend)
                    .put("visible", p.optBoolean("visible", layer.getBoolean("visible")))
                    .put("locked", p.optBoolean("locked", layer.getBoolean("locked")))
            }
            "LAYER_MOVE_STEP" -> {
                val (index, layer) = find(p.getString("id"))
                val direction = p.getString("direction")
                require(direction == "up" || direction == "down") { "图层移动方向必须是 up 或 down" }
                val siblings = (0 until layers.length()).filter {
                    layers.getJSONObject(it).optString("parentId") == layer.optString("parentId")
                }
                val siblingPosition = siblings.indexOf(index)
                val next = siblingPosition + if (direction == "up") 1 else -1
                require(next in siblings.indices) { "图层已在当前组的最" + if (direction == "up") "上方" else "下方" }
                val otherIndex = siblings[next]
                val other = layers.getJSONObject(otherIndex)
                layers.put(index, other)
                layers.put(otherIndex, layer)
            }
            "LAYER_MOVE" -> {
                val (index, layer) = find(p.getString("id"))
                val position = p.getInt("index").also { require(it in 0 until layers.length()) }
                val copy = (0 until layers.length()).map { layers.getJSONObject(it) }.toMutableList()
                copy.removeAt(index); copy.add(position, layer)
                state.put("layers", JSONArray(copy))
            }
            "LAYER_DELETE" -> {
                val (index, layer) = find(p.getString("id"))
                require(!layer.getBoolean("locked"))
                require((0 until layers.length()).none { layers.getJSONObject(it).optString("parentId") == p.getString("id") }) {
                    "请先删除或移出组中的图层"
                }
                layers.remove(index)
                if (state.optString("selectedLayerId") == p.getString("id")) {
                    val remaining = (0 until layers.length()).map { layers.getJSONObject(it) }
                    val replacement = remaining.lastOrNull {
                        it.optString("parentId") == layer.optString("parentId")
                    } ?: remaining.firstOrNull {
                        it.getString("id") == layer.optString("parentId")
                    } ?: remaining.lastOrNull()
                    state.put("selectedLayerId", replacement?.getString("id") ?: "")
                }
            }
            "LAYER_COPY" -> {
                val (_, original) = find(p.getString("id"))
                val mapping = mutableMapOf(original.getString("id") to p.getString("newId"))
                val subtree = mutableListOf(original)
                var cursor = 0
                while (cursor < subtree.size) {
                    val parent = subtree[cursor++]
                    for (i in 0 until layers.length()) {
                        val child = layers.getJSONObject(i)
                        if (child.optString("parentId") == parent.getString("id")) {
                            mapping[child.getString("id")] = UUID.nameUUIDFromBytes(
                                "${p.getString("newId")}:${child.getString("id")}".toByteArray()).toString()
                            subtree.add(child)
                        }
                    }
                }
                subtree.forEach { source ->
                    val copy = JSONObject(source.toString())
                    copy.put("id", mapping.getValue(source.getString("id")))
                    copy.put("parentId", mapping[source.optString("parentId")] ?: source.optString("parentId"))
                    copy.put("name", source.getString("name") + " 副本")
                    copy.optJSONObject("colorize")?.let {data ->
                        val sourceId=data.getString("sourceLayerId")
                        if(mapping.containsKey(sourceId))data.put("sourceLayerId",mapping.getValue(sourceId))
                    }
                    copy.optJSONArray("shapes")?.let { shapes ->
                        for (i in 0 until shapes.length()) shapes.getJSONObject(i).put("id",
                            UUID.nameUUIDFromBytes((copy.getString("id") + ":shape:" + i).toByteArray()).toString())
                    }
                    val strokes = copy.getJSONArray("strokes")
                    for (i in 0 until strokes.length()) {
                        val stroke = strokes.getJSONObject(i)
                        stroke.put("id", UUID.nameUUIDFromBytes("${copy.getString("id")}:$i".toByteArray()).toString())
                        stroke.put("layerId", copy.getString("id"))
                    }
                    copy.optJSONArray("contentOrder")?.let { order ->
                        val oldStrokes = source.getJSONArray("strokes")
                        val newStrokes = copy.getJSONArray("strokes")
                        for (i in 0 until order.length()) {
                            val event = order.getJSONObject(i)
                            if (event.optString("kind") == "stroke") {
                                val oldId = event.getString("id")
                                val index = (0 until oldStrokes.length()).firstOrNull {
                                    oldStrokes.getJSONObject(it).getString("id") == oldId
                                }
                                if (index != null) event.put("id", newStrokes.getJSONObject(index).getString("id"))
                            }
                        }
                    }
                    layers.put(copy)
                }
                if (p.optBoolean("select", false)) state.put("selectedLayerId", p.getString("newId"))
            }
            "STROKE_ADD" -> {
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !lockedByParent(layer, layers))
                val points = p.getJSONArray("points")
                require(points.length() in 1..10000)
                for (i in 0 until points.length()) {
                    val point = points.getJSONArray(i)
                    require(point.length() in 2..(if(p.has("brush"))6 else 3))
                    require(point.getDouble(0).isFinite() && point.getDouble(1).isFinite())
                    if (point.length() >= 3) require(point.getDouble(2) in 0.0..1.0)
                }
                p.optJSONObject("selection")?.let {ArtSelection.validate(it);ArtShapes.matrix(p.getJSONArray("selectionToLayer"))}
                if(p.has("brush"))ArtBrush.validateStored(p)
                if(p.has("figureVersion"))ArtFigure.validateStored(p)
                if(p.has("pathVersion"))ArtRasterPath.validateStored(p)
                requireColor(p.optString("color", "#FF000000"))
                require(p.getDouble("width") in 0.1..512.0)
                require(p.optDouble("opacity", 1.0) in 0.0..1.0)
                val tool = p.optString("tool", "pencil")
                require(tool in setOf("pencil", "ink", "eraser", "soft", "spray", "mirror", "dyna", "calligraphy",
                    "line", "rectangle", "ellipse", "polygon", "polyline", "bezier", "gradient"))
                require(when (tool) {
                    "line" -> p.has("brush") || points.length()==2
                    "rectangle", "ellipse" -> p.has("figureVersion") || points.length()==2
                    "gradient" -> points.length()==2
                    "polygon" -> points.length() >= 3
                    "polyline" -> points.length() >= 2
                    "bezier" -> p.has("pathVersion") || (points.length() in 4..1024 && (points.length() - 1) % 3 == 0)
                    else -> true
                }) { "形状顶点数量无效" }
                if (p.optBoolean("fillShape", false))
                    require(tool in setOf("rectangle", "ellipse", "polygon")) {
                        "只有闭合形状可使用前景色填充"
                    }
                if (tool == "calligraphy") {
                    val angle = p.optDouble("nibAngle", 45.0)
                    require(angle.isFinite() && angle in 0.0..180.0) {
                        "书法笔尖角度必须在 0–180° 之间"
                    }
                    p.put("nibAngle", angle)
                }
                if (tool == "dyna") {
                    val mass = p.optDouble("mass", 0.5)
                    val drag = p.optDouble("drag", 0.15)
                    require(mass.isFinite() && mass in 0.0..1.0 &&
                        drag.isFinite() && drag in 0.0..1.0) { "动态画笔的惯性和阻力必须在 0–1 之间" }
                    p.put("mass", mass).put("drag", drag)
                }
                if (tool == "mirror" && !p.has("brush")) {
                    val direction = p.optString("mirrorDirection", "vertical")
                    require(direction in setOf("vertical", "horizontal", "quad", "radial", "snowflake",
                        "translate", "copytranslate", "interval")) {
                        "对称方式无效"
                    }
                    val count = p.optInt("mirrorCount", 6)
                    require(count in 2..12) { "旋转对称画笔数必须在 2–12 之间" }
                    // Store the axis in the stroke event so history, export and AI replay
                    // retain the original mirror center even after the canvas is cropped.
                    val radius = p.optDouble("mirrorRadius", 80.0)
                    require(radius.isFinite() && radius in 0.0..512.0) {
                        "随机平移半径必须在 0–512 px 之间"
                    }
                    val seed = if (p.has("mirrorSeed")) p.getInt("mirrorSeed")
                        else kotlin.random.Random.nextInt(Int.MAX_VALUE)
                    require(seed >= 0) { "随机种子不能为负数" }
                    val centers = p.optJSONArray("mirrorCenters") ?: JSONArray()
                    require(centers.length() <= 11) { "子画笔数量不能超过 11 支" }
                    for (i in 0 until centers.length()) {
                        val point = centers.getJSONArray(i)
                        require(point.length() == 2 && point.getDouble(0).isFinite() &&
                            point.getDouble(1).isFinite()) { "子画笔位置无效" }
                        require(point.getDouble(0) in 0.0..state.getDouble("width") &&
                            point.getDouble(1) in 0.0..state.getDouble("height")) {
                            "子画笔位置不在画布范围内"
                        }
                    }
                    p.put("mirrorCenters", centers)
                    val intervalX = p.optInt("mirrorIntervalX", 1024)
                    val intervalY = p.optInt("mirrorIntervalY", 1024)
                    require(intervalX in 128..2048 && intervalY in 128..2048) {
                        "横纵间隔必须在 128–2048 px 之间"
                    }
                    val canvasWidth = state.getInt("width")
                    val canvasHeight = state.getInt("height")
                    if (direction == "interval") require(
                        (canvasWidth / intervalX + 1) * (canvasHeight / intervalY + 1) <= 48
                    ) { "间隔复制画笔超过 48 支，请加大间隔" }
                    p.put("mirrorIntervalX", intervalX).put("mirrorIntervalY", intervalY)
                        .put("canvasWidth", canvasWidth).put("canvasHeight", canvasHeight)
                    p.put("mirrorDirection", direction).put("mirrorCount", count)
                        .put("mirrorRadius", radius).put("mirrorSeed", seed)
                    val axisX = p.optDouble("axisX", state.getInt("width") / 2.0)
                    val axisY = p.optDouble("axisY", state.getInt("height") / 2.0)
                    require(axisX.isFinite() && axisX in 0.0..state.getDouble("width") &&
                        axisY.isFinite() && axisY in 0.0..state.getDouble("height")) {
                        "镜像轴不在画布范围内"
                    }
                    p.put("axisX", axisX).put("axisY", axisY)
                }
                if (tool == "gradient" && p.has("gradientVersion"))ArtGradient.validateStored(p)
                if (tool == "gradient" && !p.has("gradientVersion")) {
                    val mode = p.optString("gradientMode", "linear")
                    require(mode in setOf("linear", "radial", "angular")) { "渐变模式无效" }
                    p.put("gradientMode", mode)
                    if (p.has("gradientEndColor"))
                        requireColor(p.getString("gradientEndColor"))
                    val a = points.getJSONArray(0); val b = points.getJSONArray(1)
                    require(kotlin.math.hypot(b.getDouble(0) - a.getDouble(0),
                        b.getDouble(1) - a.getDouble(1)) >= 0.01) { "请拖出渐变方向" }
                }
                layer.getJSONArray("strokes").put(JSONObject(p.toString()))
                layer.optJSONArray("contentOrder")?.put(JSONObject().put("kind", "stroke")
                    .put("id", p.getString("id")))
            }
            "STROKE_ERASE" -> {
                val layer = find(p.getString("layerId")).second
                require(!lockedByParent(layer, layers))
                val strokes = layer.getJSONArray("strokes")
                val index = (0 until strokes.length()).firstOrNull { strokes.getJSONObject(it).getString("id") == p.getString("strokeId") }
                    ?: error("笔画不存在")
                strokes.remove(index)
            }
            "TRANSFORM_AFFINE" -> {
                val layer=find(p.getString("layerId")).second;require(!lockedByParent(layer,layers))
                val affine=ArtShapes.matrix(p.getJSONArray("affine"));if(affine.isIdentity)layer.remove("affine") else layer.put("affine",JSONArray(p.getJSONArray("affine").toString()))
            }
            "TRANSFORM_PIXELS" -> {
                val layer=find(p.getString("layerId")).second;require(!lockedByParent(layer,layers))
                ArtTransformPixels.validate(p);validateId(p.getString("asset"))
                if(p.getString("scope")=="layer") {
                    // Rasterization is explicit; historical operations retain all original assets and editable objects for Undo.
                    val deleted=mutableSetOf<String>();var frontier=setOf(layer.getString("id"))
                    repeat(layers.length()) {
                        frontier=(0 until layers.length()).map {layers.getJSONObject(it)}.filter {it.optString("parentId") in frontier}.map {it.getString("id")}.toSet()
                        deleted.addAll(frontier)
                    }
                    for(i in layers.length()-1 downTo 0)if(layers.getJSONObject(i).getString("id") in deleted)layers.remove(i)
                    for(key in listOf("asset","width","height","text","shapes","colorize","cropClip","contentOrder"))layer.remove(key)
                    layer.put("kind","paint").put("strokes",JSONArray())
                    state.remove("shapeSelection")
                } else require(layer.getString("kind") in setOf("paint","image"))
                contentOrder(layer).put(JSONObject(p.toString()).put("kind","transform_pixels"))
                if(p.getString("scope")=="selection")state.put("selection",JSONObject(p.getJSONObject("selection").toString()))
                state.put("selectedLayerId",layer.getString("id"))
            }
            "MOVE_LAYER" -> {
                val layer=find(p.getString("layerId")).second
                require(!lockedByParent(layer,layers))
                for(key in listOf("x","y")) {val value=p.getDouble(key);require(value.isFinite() && kotlin.math.abs(value)<=1000000);layer.put(key,value)}
                state.put("selectedLayerId",layer.getString("id"))
            }
            "MOVE_PIXELS" -> {
                val layer=find(p.getString("layerId")).second
                require(layer.getString("kind") in setOf("paint","image") && !lockedByParent(layer,layers))
                ArtMovePixels.validate(p);validateId(p.getString("asset"));ArtSelection.validate(p.getJSONObject("selection"))
                contentOrder(layer).put(JSONObject(p.toString()).put("kind","move_pixels"))
                state.put("selection",JSONObject(p.getJSONObject("selection").toString()))
                state.put("selectedLayerId",layer.getString("id"))
            }
            "TRANSFORM" -> {
                val layer = find(p.getString("id")).second
                require(!lockedByParent(layer, layers))
                for (field in listOf("x", "y", "rotation", "scale")) if (p.has(field)) {
                    val value = p.getDouble(field)
                    require(value.isFinite() && (field != "scale" || value in 0.01..100.0))
                    layer.put(field, value)
                }
            }
            "SELECTION_TOOL", "SELECTION_BEZIER" -> {
                val selected=p.getJSONObject("selection");ArtSelection.validate(selected)
                state.put("selection",JSONObject(selected.toString()))
            }
            "SELECTION_CREATE" -> {
                ArtSelection.validate(p)
                state.put("selection", JSONObject(p.toString()))
            }
            "SELECTION_CLEAR" -> {
                state.optJSONObject("selection")?.let { state.put("previousSelection",JSONObject(it.toString())) }
                state.put("selection", JSONObject.NULL)
            }
            "SELECTION_EDIT" -> {
                val selection = state.optJSONObject("selection") ?: error("请先创建选区")
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !lockedByParent(layer, layers))
                val transform = layerMatrix(layer, layers)
                val inverse = Matrix().also { require(transform.invert(it)) { "图层变换不可逆" } }
                val strokes = layer.getJSONArray("strokes")
                val chosen = (0 until strokes.length()).filter { index ->
                    intersects(strokes.getJSONObject(index).getJSONArray("points"), selection, transform)
                }
                require(chosen.isNotEmpty()) { "选区中没有笔画" }
                val action = p.getString("action")
                require(action != "ROTATE" || selection.optString("shape", "rect") == "rect") {
                    "非矩形选区的旋转需要可旋转蒙版，暂不可用"
                }
                val canvasCenterX = selection.getDouble("x") + selection.getDouble("width") / 2.0
                val canvasCenterY = selection.getDouble("y") + selection.getDouble("height") / 2.0
                val center = floatArrayOf(canvasCenterX.toFloat(), canvasCenterY.toFloat())
                inverse.mapPoints(center)
                val centerX = center[0].toDouble(); val centerY = center[1].toDouble()
                val movement = floatArrayOf(p.optDouble("dx", 0.0).toFloat(), p.optDouble("dy", 0.0).toFloat())
                inverse.mapVectors(movement)
                val copyOffset = floatArrayOf(20f, 20f)
                inverse.mapVectors(copyOffset)
                when (action) {
                    "DELETE" -> for (index in chosen.asReversed()) strokes.remove(index)
                    "COPY", "MOVE", "SCALE", "ROTATE" -> {
                        val source = chosen.map { JSONObject(strokes.getJSONObject(it).toString()) }
                        for ((ordinal, stroke) in source.withIndex()) {
                            val points = stroke.getJSONArray("points")
                            for (i in 0 until points.length()) {
                                val point = points.getJSONArray(i)
                                val x = point.getDouble(0)
                                val y = point.getDouble(1)
                                val transformed = when (action) {
                                    "MOVE" -> (x + movement[0]) to (y + movement[1])
                                    "SCALE" -> {
                                        val factor = p.getDouble("factor").also { require(it in 0.01..100.0) }
                                        (centerX + (x - centerX) * factor) to (centerY + (y - centerY) * factor)
                                    }
                                    "ROTATE" -> {
                                        val angle = Math.toRadians(p.getDouble("degrees"))
                                        val dx = x - centerX; val dy = y - centerY
                                        (centerX + dx * cos(angle) - dy * sin(angle)) to
                                            (centerY + dx * sin(angle) + dy * cos(angle))
                                    }
                                    else -> (x + copyOffset[0]) to (y + copyOffset[1])
                                }
                                require(transformed.first.isFinite() && transformed.second.isFinite())
                                point.put(0, transformed.first).put(1, transformed.second)
                            }
                            if (action == "COPY") stroke.put("id", "${p.getString("copyId")}:$ordinal")
                            else strokes.put(chosen[ordinal], stroke)
                            if (action == "COPY") {
                                strokes.put(stroke)
                                layer.optJSONArray("contentOrder")?.put(
                                    JSONObject().put("kind", "stroke").put("id", stroke.getString("id")))
                            }
                        }
                    }
                    else -> error("未知选区操作")
                }
                when (action) {
                    "MOVE" -> {
                        selection.put("x", selection.getDouble("x") + p.getDouble("dx"))
                        selection.put("y", selection.getDouble("y") + p.getDouble("dy"))
                    }
                    "SCALE" -> {
                        val factor = p.getDouble("factor")
                        selection.put("width", selection.getDouble("width") * factor)
                        selection.put("height", selection.getDouble("height") * factor)
                        selection.put("x", canvasCenterX - selection.getDouble("width") / 2)
                        selection.put("y", canvasCenterY - selection.getDouble("height") / 2)
                    }
                    "COPY" -> {
                        selection.put("x", selection.getDouble("x") + 20.0)
                        selection.put("y", selection.getDouble("y") + 20.0)
                    }
                }
            }
            "LAYER_CROP" -> {
                val layer=find(p.getString("layerId")).second
                require(!lockedByParent(layer,layers)) {"图层已锁定"}
                val clip=p.getJSONArray("clip");require(clip.length()==0 || clip.length() in 3..128)
                for(i in 0 until clip.length()) {
                    val point=clip.getJSONArray(i);require(point.length()==2)
                    require((0..1).all {point.getDouble(it).isFinite() && kotlin.math.abs(point.getDouble(it))<=100000000})
                }
                layer.put("cropClip",JSONArray(clip.toString()))
            }
            "CROP", "CANVAS_RESIZE" -> {
                ArtImagePolicy.requireDimensions(p.getInt("width"), p.getInt("height"))
                state.put("width", p.getInt("width"))
                state.put("height", p.getInt("height"))
                val dx = p.optDouble("x", 0.0); val dy = p.optDouble("y", 0.0)
                require(dx.isFinite() && dy.isFinite())
                if (type == "CANVAS_RESIZE") require(kotlin.math.abs(dx) <= ArtImagePolicy.MAX_EDGE &&
                    kotlin.math.abs(dy) <= ArtImagePolicy.MAX_EDGE) { "画布偏移必须在 -16384–16384 像素之间" }
                for (i in 0 until layers.length()) {
                    val layer = layers.getJSONObject(i)
                    if (layer.optString("parentId").isBlank()) {
                        layer.put("x", layer.getDouble("x") - dx)
                        layer.put("y", layer.getDouble("y") - dy)
                    }
                }
                // Reference matrices are document-space geometry, like assistant points.
                for(reference in ArtReferences.items(state)) {
                    val matrix=reference.getJSONArray("matrix")
                    matrix.put(4,matrix.getDouble(4)-dx);matrix.put(5,matrix.getDouble(5)-dy)
                }
                for(a in ArtAssistants.items(state)) {
                    a.put("points",JSONArray(ArtAssistants.points(a).map { point ->
                        AssistantPoint(point.x-dx,point.y-dy).json()
                    }))
                    if(a.has("localBounds"))a.getJSONObject("localBounds").apply {
                        put("x",getDouble("x")-dx);put("y",getDouble("y")-dy)
                    }
                }
                state.put("selection", JSONObject.NULL)
            }
            else -> error("未知画室操作：$type")
        }
    }

    private fun historyStacks(operations: JSONArray): Pair<List<String>, List<String>> {
        val undoStack = mutableListOf<String>()
        val redoStack = mutableListOf<String>()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val id = op.getString("id")
            val target = op.optJSONObject("parameters")?.optString("targetId") ?: ""
            when (op.getString("type")) {
                "REVERT" -> { undoStack.remove(target); redoStack.add(target) }
                "RESTORE" -> { redoStack.remove(target); undoStack.add(target) }
                else -> { undoStack.add(id); redoStack.clear() }
            }
        }
        return undoStack to redoStack
    }

    fun history(actor: String, redo: Boolean): JSONObject = locked {
        val (undoStack, redoStack) = historyStacks(loadCurrent().getJSONArray("operations"))
        val target = if (redo) redoStack.lastOrNull() else undoStack.lastOrNull()
        require(target != null) { if (redo) "没有可重做的操作" else "没有可撤销的操作" }
        // History commits under the same lock; the replay validates dependencies before writing.
        appendToCurrent(actor, if (redo) "RESTORE" else "REVERT", JSONObject().put("targetId", target))
    }

    /** Jump between reachable states as one atomic history change. */
    fun historyJump(actor: String, targetId: String, expectedRevision: Int): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
        val operations = doc.getJSONArray("operations")
        require(expectedRevision == operations.length()) {
            "工程已由另一端更新，请刷新足迹再操作"
        }
        val (undoStack, redoStack) = historyStacks(operations)
        val reachable = undoStack + redoStack.asReversed()
        val destination = if (targetId.isEmpty()) 0 else reachable.indexOf(targetId) + 1
        require(destination in 0..reachable.size &&
            (targetId.isEmpty() || destination > 0)) { "足迹状态已不在当前分支" }
        val current = undoStack.size
        if (destination == current) return@locked snapshot(doc)
        val changes = if (destination < current) {
            undoStack.subList(destination, current).asReversed().map { "REVERT" to it }
        } else {
            redoStack.asReversed().take(destination - current).map { "RESTORE" to it }
        }
        for ((type, id) in changes) {
            operations.put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("actor", actor).put("type", type)
                .put("parameters", JSONObject().put("targetId", id))
                .put("timestamp", System.currentTimeMillis()))
        }
        // Replay validates every dependency before the draft is written.
        val result = snapshot(doc)
        atomic(draft(doc.getString("id")), doc.toString())
        result.put("lastOperationId", operations.getJSONObject(operations.length() - 1).getString("id"))
    }

    fun clipboardInfo(): JSONObject = locked {
        if (editClipboard.isFile) JSONObject(editClipboard.readText()) else JSONObject()
    }

    private fun editingRectangle(state: JSONObject): IntArray {
        val canvasWidth = state.getInt("width")
        val canvasHeight = state.getInt("height")
        val selected = state.optJSONObject("selection")
        if (selected == null) return intArrayOf(0, 0, canvasWidth, canvasHeight)
        val left = kotlin.math.floor(selected.getDouble("x")).toInt().coerceIn(0, canvasWidth)
        val top = kotlin.math.floor(selected.getDouble("y")).toInt().coerceIn(0, canvasHeight)
        val right = kotlin.math.ceil(selected.getDouble("x") + selected.getDouble("width"))
            .toInt().coerceIn(0, canvasWidth)
        val bottom = kotlin.math.ceil(selected.getDouble("y") + selected.getDouble("height"))
            .toInt().coerceIn(0, canvasHeight)
        require(right > left && bottom > top) { "选区不在画布范围内" }
        return intArrayOf(left, top, right - left, bottom - top)
    }

    private fun copyableLayer(state: JSONObject): JSONObject {
        val layers = state.getJSONArray("layers")
        val selected = state.optString("selectedLayerId")
        val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
            .firstOrNull { it.getString("id") == selected } ?: error("请先选择图层")
        require(layer.getString("kind") in setOf("paint", "image") &&
            layer.getBoolean("visible") && layer.optString("parentId").isBlank()) {
            "请选择可见的根绘画图层或图像图层"
        }
        return layer
    }

    private fun editableLayer(state: JSONObject): JSONObject {
        val layers = state.getJSONArray("layers")
        val id = state.optString("selectedLayerId")
        val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: error("请先选择图层")
        require(layer.getString("kind") == "paint" || layer.getString("kind") == "image") {
            "当前图层不支持像素编辑"
        }
        require(!lockedByParent(layer, layers) && layer.getBoolean("visible")) {
            "图层已锁定或隐藏"
        }
        require(layer.optString("parentId").isBlank() &&
            layer.getDouble("x") == 0.0 && layer.getDouble("y") == 0.0 &&
            layer.getDouble("scale") == 1.0 && layer.getDouble("rotation") == 0.0 && !layer.has("affine")) {
            "请先取消图层变换和分组，再编辑选区像素"
        }
        return layer
    }

    fun copyPixels(actor: String, merged: Boolean = false, cut: Boolean = false): JSONObject = locked {
        copyPixelsUnlocked(actor, merged, cut)
    }

    private fun copyPixelsUnlocked(actor: String, merged: Boolean = false, cut: Boolean = false): JSONObject {
        require(actor == "AWEI" || actor == "LANER")
        require(!merged || !cut)
        val doc = loadCurrent()
        val state = replay(doc)
        val area = editingRectangle(state)
        val layer = if (merged) null else if (cut) editableLayer(state) else copyableLayer(state)
        val view = snapshot(doc)
        if (layer != null) {
            val imageState = view.getJSONObject("state")
            imageState.put("background", "#00000000")
            val only = imageState.getJSONArray("layers")
            for (i in 0 until only.length()) {
                val candidate = only.getJSONObject(i)
                if (candidate.getString("id") != layer.getString("id")) {
                    candidate.put("visible", false)
                } else {
                    // Copy the selected layer's pixels; compositing belongs to merged copy.
                    candidate.put("blend", "normal").put("opacity", 1.0)
                }
            }
        }
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this, view.getJSONObject("state"),
            state.getInt("width"), state.getInt("height")) + area[2].toLong() * area[3] * 8, "复制选区")
        val bitmap = ArtRenderer.render(this, view)
        val bytes = try {
            val clipped = Bitmap.createBitmap(area[2], area[3], Bitmap.Config.ARGB_8888)
            try {
                Canvas(clipped).drawBitmap(bitmap, -area[0].toFloat(), -area[1].toFloat(), Paint())
                state.optJSONObject("selection")?.takeIf {it.has("coverage")}?.let {
                    ArtSoftSelection.maskBitmap(clipped,it,area[0],area[1])
                }
                state.optJSONObject("selection")?.takeIf {
                    !it.has("coverage") && it.optString("shape", "rect") != "rect"
                }?.let { selected ->
                    // A small reusable strip avoids allocating a second full 4K bitmap.
                    val mask = Bitmap.createBitmap(area[2], minOf(area[3], 128),
                        Bitmap.Config.ARGB_8888)
                    try {
                        val maskCanvas = Canvas(mask)
                        val destination = Canvas(clipped)
                        val selectionPath = ArtSelection.path(selected)
                        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        }
                        for (row in 0 until area[3] step mask.height) {
                            mask.eraseColor(Color.TRANSPARENT)
                            maskCanvas.save()
                            maskCanvas.translate(-area[0].toFloat(), -(area[1] + row).toFloat())
                            maskCanvas.drawPath(selectionPath, shapePaint)
                            maskCanvas.restore()
                            destination.drawBitmap(mask, 0f, row.toFloat(), clipPaint)
                        }
                    } finally { mask.recycle() }
                }
                ByteArrayOutputStream().use { stream ->
                    require(clipped.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
            } finally { clipped.recycle() }
        } finally { bitmap.recycle() }
        require(bytes.size <= 8 * 1024 * 1024) { "选区图片超过 8 MB" }
        val asset = UUID.randomUUID().toString()
        atomicBytes(assetFile(asset), bytes)
        val clipboard = JSONObject().put("asset", asset).put("width", area[2]).put("height", area[3])
            .put("sourceActor", actor).put("sourceDocument", doc.getString("id"))
        atomic(editClipboard, clipboard.toString())
        if (cut) {
            val params = JSONObject().put("layerId", layer!!.getString("id"))
                .put("mode", "CLEAR").put("x", area[0]).put("y", area[1])
                .put("width", area[2]).put("height", area[3])
            state.optJSONObject("selection")?.let {
                params.put("selection", JSONObject(it.toString()))
            }
            appendToCurrent(actor, "PIXEL_EDIT", params)
        }
        return clipboard.put("cut", cut)
    }

    fun pastePixels(actor: String, intoActive: Boolean = false,
                    atX: Int? = null, atY: Int? = null): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        require(editClipboard.isFile) { "画室剪贴板为空" }
        val clip = JSONObject(editClipboard.readText())
        val asset = clip.getString("asset")
        require(assetFile(asset).isFile) { "画室剪贴板图片已丢失" }
        val state = replay(loadCurrent())
        require((atX == null) == (atY == null)) { "请同时提供光标 X 与 Y" }
        if (atX != null && atY != null) {
            require(atX in 0..state.getInt("width") && atY in 0..state.getInt("height")) {
                "光标位置超出画布"
            }
        }
        val x = if (atX != null) atX - clip.getInt("width") / 2
            else (state.getInt("width") - clip.getInt("width")) / 2
        val y = if (atY != null) atY - clip.getInt("height") / 2
            else (state.getInt("height") - clip.getInt("height")) / 2
        val mode = if (intoActive) "PIXEL_PASTE" else "PASTE_IMAGE"
        val target = if (intoActive) editableLayer(state).getString("id") else ""
        appendToCurrent(actor, mode, JSONObject().put("asset", asset).put("id", UUID.randomUUID().toString())
            .put("layerId", target).put("x", x).put("y", y))
    }

    fun pasteAsNew(actor: String, confirmResize: JSONObject? = null): JSONObject {
        val clip = clipboardInfo()
        require(clip.has("asset")) { "画室剪贴板为空" }
        ArtImagePolicy.requireDimensions(clip.getInt("width"), clip.getInt("height"))
        val bytes = locked { assetFile(clip.getString("asset")).readBytes() }
        return openImage(Base64.encodeToString(bytes, Base64.NO_WRAP), "粘贴图像", actor, confirmResize)
    }

    /** Reference rendering and the pixel receipt share the document lock with revision checks. */
    fun encloseFill(actor: String,p: JSONObject): JSONObject = locked {
        require(actor in setOf("AWEI","LANER"))
        val doc=loadCurrent()
        require(p.getString("documentId")==doc.getString("id")) {"工程已切换，请重新围合"}
        require(p.getInt("expectedRevision")==doc.getJSONArray("operations").length()) {"工程已更新，请重新围合"}
        val state=replay(doc);val layer=editableLayer(state);ArtEncloseFill.target(layer)
        require(p.getString("layerId")==layer.getString("id")) {"目标图层已改变，请重新围合"}
        val settings=ArtEncloseFill.options(p)
        val color=if(settings.getBoolean("erase"))Color.WHITE else Color.parseColor(p.getString("color").also {requireColor(it)})
        require(settings.getBoolean("erase") || settings.getString("fillType")=="pattern" || Color.alpha(color)>0) {"填充色不能完全透明，请使用擦除模式"}
        val request=ArtEncloseFill.geometry(p,settings).put("layerId",layer.getString("id"))
        val points=if(request.has("points"))ArtEncloseFill.points(request.getJSONArray("points")) else emptyList()
        val area=ArtEncloseFill.area(state,request,points)
        val source=selectionReference(snapshot(doc),request,area.rect.width().toLong()*area.rect.height()*96)
        val resources=mutableMapOf<String,Bitmap>();var overlay:Bitmap?=null
        try {
            val result=ArtEncloseFill.solve(state,source,request,area,color) {id->resources.getOrPut(id) {
                val file=assetFile(id);require(file.isFile) {"图案图片资源不存在"}
                val size=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,size)
                require(size.outWidth in 1..512 && size.outHeight in 1..512) {"图案图片边长须为1–512像素，请用brush.resource.import导入"}
                ArtImagePolicy.decodeAsset(file)
            }}
            overlay=result.bitmap
            val receipt=JSONObject().put("filledPixels",result.pixels).put("selectedRegions",result.regions)
                .put("algorithm","shared-soft-enclose-v2").put("reference",settings.getString("reference")).put("blend",settings.getString("blend"))
            if(result.pixels==0)return@locked snapshot(doc).put("encloseResult",receipt.put("changed",false).put("message","没有符合条件的可写入区域"))
            val bytes=ArtImagePolicy.encodePng(result.bitmap,MAX_ASSET_BYTES);val asset=UUID.randomUUID().toString()
            try {
                atomicBytes(assetFile(asset),bytes)
                val params=JSONObject().put("asset",asset).put("layerId",layer.getString("id"))
                    .put("action",if(settings.getBoolean("erase"))"ENCLOSE_ERASE" else "ENCLOSE_FILL")
                    .put("x",area.rect.left).put("y",area.rect.top).put("width",area.rect.width()).put("height",area.rect.height())
                    .put("settings",settings).put("blend",settings.getString("blend"))
                    .put("filledPixels",result.pixels).put("algorithm","shared-soft-enclose-v2")
                val geometry=if(request.has("nodes"))"nodes" else "points";params.put(geometry,request.getJSONArray(geometry))
                appendToCurrent(actor,"PIXEL_PASTE",params).put("encloseResult",receipt.put("changed",true))
            } catch(error:Throwable) {assetFile(asset).delete();throw error}
        } finally {source.recycle();overlay?.recycle();resources.values.forEach {it.recycle()}}
    }

    /** Compute pixels under the same document lock; only a complete result enters history. */
    fun smartPatch(actor: String,p: JSONObject): JSONObject = locked {
        require(actor in setOf("AWEI","LANER"))
        val doc=loadCurrent()
        require(p.getString("documentId")==doc.getString("id")) { "工程已切换，请重新涂抹修补区域" }
        require(p.getInt("expectedRevision")==doc.getJSONArray("operations").length()) { "工程版本已更新，请重新涂抹修补区域" }
        val state=replay(doc);val layer=editableLayer(state)
        require(p.getString("layerId")==layer.getString("id")) { "当前图层已改变，请重新选择修补目标" }
        val mask=ArtSmartPatch.mask(state,p)
        val view=ArtMenuOperations.isolated(snapshot(doc),setOf(layer.getString("id")),layer.getString("id"))
        val viewState=view.getJSONObject("state");viewState.put("background","#00000000")
        ArtMenuOperations.layers(viewState).first { it.getString("id")==layer.getString("id") }
            .put("visible",true).put("opacity",1.0).put("blend","normal")
        ArtImagePolicy.requireBytes(ArtImagePolicy.renderBytes(this,viewState,state.getInt("width"),state.getInt("height"))+
            ArtSmartPatch.workingBytes(mask.width,mask.height),"智能修补")
        val source=ArtRenderer.render(this,view)
        val result=try { ArtSmartPatch.repair(source,mask,state.optJSONObject("selection")) } finally { source.recycle() }
        val patchBytes: ByteArray;val eraseBytes: ByteArray
        try {
            patchBytes=ArtImagePolicy.encodePng(result.patch,MAX_ASSET_BYTES)
            eraseBytes=ArtImagePolicy.encodePng(result.erase,MAX_ASSET_BYTES)
        } finally { result.patch.recycle();result.erase.recycle() }
        val patchId=UUID.randomUUID().toString();val eraseId=UUID.randomUUID().toString()
        // Both references use the standard nested asset key, so archive, cleanup and import see them.
        try {
            atomicBytes(assetFile(patchId),patchBytes);atomicBytes(assetFile(eraseId),eraseBytes)
            val params=JSONObject().put("layerId",layer.getString("id")).put("x",mask.left).put("y",mask.top)
                .put("width",mask.width).put("height",mask.height)
                .put("patch",JSONObject().put("asset",patchId)).put("erase",JSONObject().put("asset",eraseId))
                .put("algorithm","multiscale-patchmatch").put("maskPixels",result.pixels)
                .put("comparisons",result.work).put("levels",ArtSmartPatch.levelReports(result.levels))
                .put("settings",JSONObject(p.toString())
                    .apply { remove("points");remove("documentId");remove("expectedRevision");remove("layerId") })
            appendToCurrent(actor,"PIXEL_REPAIR",params).put("repair",JSONObject()
                .put("maskPixels",result.pixels).put("comparisons",result.work).put("algorithm","multiscale-patchmatch")
                .put("levels",ArtSmartPatch.levelReports(result.levels)).put("nativeOutput",true))
        } catch(error:Throwable) {assetFile(patchId).delete();assetFile(eraseId).delete();throw error}
    }

    /** Legacy positional callers share the same engine and defaults as the structured entrance. */
    fun fillContiguous(actor:String,x:Int,y:Int,color:String,expectedRevision:Int?=null,tolerance:Int=0,
        referenceAllLayers:Boolean=false,erase:Boolean=false):JSONObject {
        val p=JSONObject().put("x",x).put("y",y).put("color",color).put("tolerance",tolerance)
            .put("referenceAllLayers",referenceAllLayers).put("erase",erase)
        if(expectedRevision!=null)p.put("expectedRevision",expectedRevision)
        return fillContiguous(actor,p)
    }
    /** Compute the complete gesture against one reference while holding the document/revision lock. */
    fun fillContiguous(actor:String,p:JSONObject):JSONObject=locked {
        require(actor in setOf("AWEI","LANER"))
        val doc=loadCurrent()
        if(p.has("documentId"))require(p.getString("documentId")==doc.getString("id")) {"工程已切换，请重新填充"}
        if(p.has("expectedRevision"))require(p.getInt("expectedRevision")==doc.getJSONArray("operations").length()) {"工程已更新，请重新填充"}
        val state=replay(doc);val layer=editableLayer(state)
        if(p.has("layerId"))require(p.getString("layerId")==layer.getString("id")) {"目标图层已改变，请重新填充"}
        val o=ArtContiguousFill.options(p);val request=JSONObject(o.toString()).put("layerId",layer.getString("id"))
        for(key in listOf("x","y","points","bounds"))if(p.has(key))request.put(key,p.get(key))
        // Even a transparent/no-op gesture validates its seed contract before returning.
        ArtContiguousFill.seeds(request,state.getInt("width"),state.getInt("height"))
        request.put("limitToSelection",o.getBoolean("useSelectionAsBoundary") && state.optJSONObject("selection")!=null)
        val bounds=ArtColorSelection.bounds(state,request)
        val source=selectionReference(snapshot(doc),request,bounds.width().toLong()*bounds.height()*64)
        val resources=mutableMapOf<String,Bitmap>()
        var overlay:Bitmap?=null
        try {
            val rawMask=ArtContiguousFill.mask(state,source,request)
            val coverage=rawMask.alpha.count {it.toInt()!=0}
            if(coverage==0 || o.getDouble("opacity")==0.0)return@locked snapshot(doc).put("changed",false).put("message","填充没有可写入的覆盖区域")
            val mask=ArtContiguousFill.crop(rawMask)
            overlay=ArtContiguousFill.paint(mask,o) {id->resources.getOrPut(id) {
                val file=assetFile(id);require(file.isFile) {"图案图片资源不存在"}
                val size=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,size)
                require(size.outWidth in 1..512 && size.outHeight in 1..512) {"图案图片边长须为1–512像素，请用brush.resource.import导入"}
                ArtImagePolicy.decodeAsset(file)
            }}
            val bitmap=requireNotNull(overlay);val row=IntArray(bitmap.width);var painted=0
            for(y in 0 until bitmap.height) {bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1);painted+=row.count {Color.alpha(it)>0}}
            if(painted==0)return@locked snapshot(doc).put("changed",false).put("message","图案或透明度没有可写入的像素")
            val bytes=ArtImagePolicy.encodePng(bitmap,MAX_ASSET_BYTES);val asset=UUID.randomUUID().toString()
            try {
                atomicBytes(assetFile(asset),bytes)
                val params=JSONObject().put("asset",asset).put("layerId",layer.getString("id"))
                    .put("action",if(o.getBoolean("erase"))"FILL_CONTIGUOUS_ERASE" else "FILL_CONTIGUOUS")
                    .put("x",mask.bounds.left).put("y",mask.bounds.top).put("width",mask.bounds.width()).put("height",mask.bounds.height())
                    .put("settings",o).put("seedInput",if(p.has("points"))JSONArray(p.getJSONArray("points").toString()) else JSONArray().put(JSONArray().put(p.getInt("x")).put(p.getInt("y"))))
                    .put("algorithm","shared-soft-multiseed-fill-v1").put("coveragePixels",coverage).put("paintedPixels",painted)
                appendToCurrent(actor,"PIXEL_PASTE",params).put("changed",true).put("fillFeedback",true)
                    .put("fillResult",JSONObject().put("coveragePixels",coverage).put("paintedPixels",painted).put("fillMode",o.getString("fillMode")))
            } catch(error:Throwable) {assetFile(asset).delete();throw error}
        } finally {source.recycle();overlay?.recycle();resources.values.forEach {it.recycle()}}
    }

    fun editPixels(actor: String, mode: String, color: String = ""): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        require(mode == "CLEAR" || mode == "FILL")
        val state = replay(loadCurrent())
        val layer = editableLayer(state)
        val area = editingRectangle(state)
        if (mode == "FILL") requireColor(color)
        val params = JSONObject().put("layerId", layer.getString("id"))
            .put("mode", mode).put("color", color).put("x", area[0]).put("y", area[1])
            .put("width", area[2]).put("height", area[3])
        state.optJSONObject("selection")?.let {
            params.put("selection", JSONObject(it.toString()))
        }
        appendToCurrent(actor, "PIXEL_EDIT", params)
    }

    private fun appendToCurrent(actor: String, type: String, params: JSONObject): JSONObject {
        val doc = loadCurrent()
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("type", type).put("parameters", params).put("timestamp", System.currentTimeMillis())
        doc.getJSONArray("operations").put(op)
        val result = snapshot(doc)
        requireRenderBudget(result)
        atomic(draft(doc.getString("id")), doc.toString())
        return result.put("lastOperationId", op.getString("id"))
    }

    private fun contentOrder(layer: JSONObject): JSONArray {
        layer.optJSONArray("contentOrder")?.let { return it }
        val order = JSONArray()
        val strokes = layer.getJSONArray("strokes")
        for (i in 0 until strokes.length()) {
            order.put(JSONObject().put("kind", "stroke").put("id", strokes.getJSONObject(i).getString("id")))
        }
        layer.put("contentOrder", order)
        return order
    }

    private fun newLayer(id: String, kind: String, name: String, parent: String, asset: String): JSONObject =
        JSONObject().put("id", id).put("kind", kind).put("name", name).put("parentId", parent)
            .put("visible", true).put("opacity", 1.0).put("locked", false).put("blend", "normal")
            .put("x", 0.0).put("y", 0.0).put("scale", 1.0).put("rotation", 0.0)
            .put("asset", asset).put("strokes", JSONArray())

    private fun lockedByParent(layer: JSONObject, layers: JSONArray): Boolean {
        var parent = layer
        repeat(layers.length() + 1) {
            if (parent.getBoolean("locked")) return true
            val id = parent.optString("parentId")
            if (id.isBlank()) return false
            parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                .firstOrNull { it.getString("id") == id } ?: error("图层组不存在")
        }
        error("图层组存在循环引用")
    }

    private fun layerMatrix(layer: JSONObject, layers: JSONArray): Matrix {
        val chain = mutableListOf(layer)
        var parentId = layer.optString("parentId")
        repeat(layers.length()) {
            if (parentId.isNotBlank()) {
                val parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == parentId } ?: error("图层组不存在")
                chain.add(parent)
                parentId = parent.optString("parentId")
            }
        }
        require(parentId.isBlank()) { "图层组存在循环引用" }
        return Matrix().apply {for(item in chain)postConcat(ArtShapes.localMatrix(item))}
    }

    private fun intersects(points: JSONArray, selection: JSONObject, matrix: Matrix): Boolean {
        if(selection.optString("shape","rect") in setOf("bezier","compound","raster")) {
            val mapped=(0 until points.length()).map {i->val p=points.getJSONArray(i)
                val q=floatArrayOf(p.getDouble(0).toFloat(),p.getDouble(1).toFloat());matrix.mapPoints(q);q[0] to q[1]}
            return ArtSelection.intersectsPath(ArtSelection.path(selection),mapped)
        }
        var previous: FloatArray? = null
        for (i in 0 until points.length()) {
            val point = points.getJSONArray(i)
            val mapped = floatArrayOf(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            matrix.mapPoints(mapped)
            if (ArtSelection.contains(selection, mapped[0].toDouble(), mapped[1].toDouble()))
                return true
            previous?.let { old ->
                if (ArtSelection.intersectsSegment(selection,
                        old[0].toDouble(), old[1].toDouble(),
                        mapped[0].toDouble(), mapped[1].toDouble())) return true
            }
            previous = mapped
        }
        return false
    }

    private fun loadCurrent(): JSONObject {
        require(pointer.isFile) { "请先新建或打开工程" }
        val id = pointer.readText().trim()
        validateId(id)
        return JSONObject(draft(id).readText())
    }

    private fun draft(id: String): File { validateId(id); return File(drafts, "$id.json") }
    private fun archive(id: String): File { validateId(id); return saveDirectories.projectFile(id) }
    fun assetFile(id: String): File { validateId(id); return File(assets, "$id.png") }
    private fun validateId(id: String) { require(id.matches(Regex("[a-f0-9-]{36}"))) { "工程标识无效" } }
    private fun requireColor(value: String) { require(value.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色必须是 #AARRGGBB" } }

    private fun atomic(file: File, value: String) = atomicBytes(file, value.toByteArray(Charsets.UTF_8))
    private fun atomicBytes(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            require(temp.renameTo(file)) { "写入工程失败" }
        } finally { temp.delete() }
    }

    internal companion object {
        private val processLock = Any()
        const val MAX_IMAGE_INPUT_BYTES = 8 * 1024 * 1024
        const val MAX_ASSET_BYTES = 64 * 1024 * 1024
    }
}
