package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Native cels retain editable strokes/assets/shapes. No rendered pixels are stored as frames. */
internal object ArtAnimation {
    const val MAX_TIME=9999
    const val MAX_KEYS=512
    val kinds=setOf("paint","image","vector")
    private val shared=setOf("id","name","kind","parentId","visible","locked","animationKeys")
    fun settings(state:JSONObject):JSONObject = state.optJSONObject("animation") ?: JSONObject()
        .put("fps",12).put("start",0).put("end",23).put("loop",true).put("onion",false).put("current",0)
    fun layers(state:JSONObject)=ArtMenuOperations.layers(state)
    fun keys(layer:JSONObject)=layer.optJSONArray("animationKeys")
    private fun contentView(layer:JSONObject):JSONObject=JSONObject().apply {
        // Read-only projection for equality checks; mutation/cel ownership always uses a deep copy.
        layer.keys().forEach {key -> if(key !in shared)put(key,layer.get(key))}
    }
    fun content(layer:JSONObject):JSONObject=ArtJsonCopy.objectValue(contentView(layer))
    private fun install(layer:JSONObject,content:JSONObject) {
        layer.keys().asSequence().toList().filter {it !in shared}.forEach {layer.remove(it)}
        content.keys().forEach {layer.put(it,content.get(it))}
    }
    fun active(layer:JSONObject,time:Int):JSONObject? {
        val keys=keys(layer) ?: return null
        return (0 until keys.length()).map {keys.getJSONObject(it)}
            .filter {it.getInt("time")<=time}.maxByOrNull {it.getInt("time")}
    }
    fun resolve(state:JSONObject,time:Int) {
        require(time in 0..MAX_TIME) {"动画帧号须为0–$MAX_TIME"}
        layers(state).forEach {layer->if(keys(layer)!=null) {
            val key=active(layer,time)
            requireNotNull(key) {"动画轨道缺少第0帧"}
            install(layer,ArtJsonCopy.objectValue(key.getJSONObject("content")))
        }}
    }
    fun before(state:JSONObject)=layers(state).filter {keys(it)!=null}
        .associate {it.getString("id") to contentView(it).toString()}
    fun capture(state:JSONObject,time:Int,before:Map<String,String>,expectedKeys:JSONObject?=null) {
        layers(state).forEach {layer->
            val id=layer.getString("id")
            if(id in before) {
                require(keys(layer)!=null) {"此操作会丢失动画轨道，请先明确停用该轨道"}
                val now=contentView(layer)
                if(now.toString()!=before.getValue(id)) {
                    if(expectedKeys!=null)require(expectedKeys.has(id) && active(layer,time)?.getInt("time")==expectedKeys.getInt(id)) {
                        "动画编辑所依赖的关键帧已撤销或移动；该选择性撤销与后续操作冲突"
                    }
                    require(layer.getString("kind") in kinds) {"该图层类型不能保留动画轨道"}
                    requireNotNull(active(layer,time)).put("content",ArtJsonCopy.objectValue(now))
                }
            }
        }
        validate(state)
    }
    fun editable(state:JSONObject,id:String):JSONObject {
        val layer=layers(state).firstOrNull {it.getString("id")==id} ?: error("动画图层不存在")
        require(layer.getString("kind") in kinds) {"目前动画轨道支持绘画、图像与矢量图层"}
        var target=layer
        repeat(layers(state).size+1) {
            require(!target.getBoolean("locked")) {"动画图层或父组已锁定"}
            val parent=target.optString("parentId")
            if(parent.isBlank())return layer
            target=layers(state).first {it.getString("id")==parent}
        }
        error("图层组存在循环")
    }
    private fun sort(layer:JSONObject) {
        val list=keys(layer)!!
        layer.put("animationKeys",JSONArray((0 until list.length()).map {list.getJSONObject(it)}.sortedBy {it.getInt("time")}))
    }
    fun prepareKey(state:JSONObject,p:JSONObject):JSONObject {
        val result=ArtJsonCopy.objectValue(p)
        val action=p.getString("action")
        if(action in setOf("blank","duplicate")) {
            val layer=editable(state,p.getString("layerId"))
            val cel=if(action=="duplicate"&&p.has("sourceFrame")) {
                val source=p.getInt("sourceFrame");require(source in 0..MAX_TIME)
                ArtJsonCopy.objectValue(requireNotNull(active(layer,source)) {"源关键帧不存在"}.getJSONObject("content"))
            } else content(layer)
            if(action=="blank") {
                cel.put("asset","").put("strokes",JSONArray())
                cel.remove("pixelEdits");cel.remove("contentOrder")
                if(layer.getString("kind")=="vector")cel.put("shapes",JSONArray())
            }
            result.put("cel",cel)
        }
        return result
    }
    fun edit(state:JSONObject,type:String,p:JSONObject) {
        val cfg=settings(state)
        when(type) {
            "ANIMATION_POSES" -> {
                ArtAnimationPoses.install(editable(state, p.getString("layerId")), p)
                state.put("animation", cfg)
                validate(state)
                resolve(state, cfg.getInt("current"))
            }
            "ANIMATION_SETTINGS" -> {
                val next=ArtJsonCopy.objectValue(cfg)
                for(name in listOf("fps","start","end","loop","onion"))if(p.has(name))next.put(name,p.get(name))
                require(next.getInt("fps") in 1..60) {"帧率须为1–60"}
                require(next.getInt("start") in 0..MAX_TIME&&next.getInt("end") in next.getInt("start")..MAX_TIME) {"播放范围无效"}
                require(next.getInt("end")-next.getInt("start")<600) {"单次播放范围最多600帧"}
                next.getBoolean("loop");next.getBoolean("onion")
                state.put("animation",next)
            }
            "ANIMATION_TIME" -> {
                val time=p.getInt("frame");require(time in 0..MAX_TIME)
                state.put("animation",ArtJsonCopy.objectValue(cfg).put("current",time))
                resolve(state,time)
                state.put("selection",JSONObject.NULL).put("shapeSelection",JSONObject.NULL)
            }
            "ANIMATION_KEY" -> {
                val time=p.getInt("frame");require(time in 0..MAX_TIME)
                val layer=editable(state,p.getString("layerId"))
                val action=p.getString("action")
                val existing=keys(layer)
                if(action in setOf("blank","duplicate")) {
                    require(existing==null || (0 until existing.length()).none {existing.getJSONObject(it).getInt("time")==time}) {"目标已有关键帧，不能覆盖"}
                    val original=content(layer)
                    if(existing==null)layer.put("animationKeys",JSONArray().apply {
                        if(time>0)put(JSONObject().put("time",0).put("content",ArtJsonCopy.objectValue(original)))
                    })
                    val cel=ArtJsonCopy.objectValue(p.getJSONObject("cel"))
                    keys(layer)!!.put(JSONObject().put("time",time).put("content",cel))
                    sort(layer)
                } else if(action=="disable") {
                    require(existing!=null) {"该图层没有动画轨道"}
                    layer.remove("animationKeys") // Bake the displayed editable cel; Undo restores all keys.
                } else {
                    requireNotNull(existing) {"该图层没有动画轨道"}
                    val index=(0 until existing.length()).firstOrNull {existing.getJSONObject(it).getInt("time")==time}
                        ?: error("该位置不是关键帧")
                    require(time!=0) {"第0帧是轨道锚点，不能删除或移动；可停用整条轨道"}
                    when(action) {
                        "remove" -> existing.remove(index)
                        "move" -> {
                            val target=p.getInt("targetFrame");require(target in 1..MAX_TIME)
                            require((0 until existing.length()).none {existing.getJSONObject(it).getInt("time")==target}) {"目标已有关键帧"}
                            existing.getJSONObject(index).put("time",target);sort(layer)
                        }
                        else -> error("未知关键帧操作")
                    }
                }
                state.put("animation",cfg)
                validate(state)
                resolve(state,cfg.getInt("current"))
            }
            else -> error("未知动画事件")
        }
    }
    fun validate(state:JSONObject) {
        var count=0
        for(layer in layers(state))keys(layer)?.let {list->
            require(layer.getString("kind") in kinds)
            require(list.length() in 1..128) {"每个动画图层最多128个关键帧"}
            count+=list.length()
            var previous=-1
            for(i in 0 until list.length()) {
                val key=list.getJSONObject(i);val time=key.getInt("time")
                require(time in 0..MAX_TIME && time>previous) {"关键帧须严格递增且唯一"}
                require(i!=0 || time==0) {"轨道须保留第0帧"}
                key.getJSONObject("content");previous=time
            }
        }
        require(count<=MAX_KEYS) {"全工程最多$MAX_KEYS 个关键帧"}
    }
    fun remapKeys(copy:JSONObject,source:JSONObject) {
        val list=keys(copy) ?: return
        val shapeIds=mutableMapOf<String,String>()
        val strokeIds=mutableMapOf<String,String>()
        fun existing(field:String,map:MutableMap<String,String>) {
            val old=source.optJSONArray(field) ?: return
            val fresh=copy.getJSONArray(field)
            for(i in 0 until old.length())map[old.getJSONObject(i).getString("id")]=fresh.getJSONObject(i).getString("id")
        }
        existing("shapes",shapeIds);existing("strokes",strokeIds)
        fun fresh(id:String,kind:String,map:MutableMap<String,String>)=map.getOrPut(id) {
            java.util.UUID.nameUUIDFromBytes((copy.getString("id")+":animation:"+kind+":"+id).toByteArray()).toString()
        }
        for(i in 0 until list.length()) {
            val cel=list.getJSONObject(i).getJSONObject("content")
            cel.optJSONArray("shapes")?.let {shapes->for(n in 0 until shapes.length()) {
                val shape=shapes.getJSONObject(n);shape.put("id",fresh(shape.getString("id"),"shape",shapeIds))
            }}
            val strokes=cel.getJSONArray("strokes")
            for(n in 0 until strokes.length()) {
                val stroke=strokes.getJSONObject(n)
                stroke.put("id",fresh(stroke.getString("id"),"stroke",strokeIds)).put("layerId",copy.getString("id"))
            }
            cel.optJSONArray("contentOrder")?.let {order->for(n in 0 until order.length()) {
                val entry=order.getJSONObject(n)
                if(entry.getString("kind")=="stroke")entry.put("id",strokeIds.getValue(entry.getString("id")))
            }}
        }
    }
    fun describe(snapshot:JSONObject):JSONObject {
        val state=snapshot.getJSONObject("state")
        val cfg=ArtJsonCopy.objectValue(settings(state))
        val rows=JSONArray()
        for(layer in layers(state))if(layer.getString("kind") in kinds) {
            val list=keys(layer)
            rows.put(JSONObject().put("id",layer.getString("id")).put("name",layer.getString("name"))
                .put("kind",layer.getString("kind")).put("visible",layer.getBoolean("visible")).put("locked",layer.getBoolean("locked"))
                .put("keyframes",JSONArray().apply {if(list!=null)for(i in 0 until list.length())put(list.getJSONObject(i).getInt("time"))})
                .put("activeKeyframe",active(layer,cfg.getInt("current"))?.getInt("time") ?: JSONObject.NULL))
        }
        return cfg.put("tracks",rows).put("documentId",snapshot.getString("id")).put("revision",snapshot.getInt("revision"))
    }
    fun frame(snapshot:JSONObject,time:Int):JSONObject {
        require(time in 0..MAX_TIME)
        val original=snapshot.getJSONObject("state")
        val state=JSONObject()
        // Materialize one cel per layer. Do not copy the event log or every inactive cel for each video frame.
        original.keys().forEach {key->if(key!="layers") {
            val value=original.get(key)
            state.put(key,when(value) {
                is JSONObject->ArtJsonCopy.objectValue(value)
                is JSONArray->ArtJsonCopy.arrayValue(value)
                else->value
            })
        }}
        val rows=JSONArray()
        for(layer in layers(original)) {
            val active=active(layer,time)
            val copy=if(active==null)ArtJsonCopy.objectValue(layer) else JSONObject().apply {
                shared.filter {it!="animationKeys"}.forEach {key->if(layer.has(key))put(key,layer.get(key))}
                val cel=ArtJsonCopy.objectValue(active.getJSONObject("content"))
                cel.keys().forEach {key->put(key,cel.get(key))}
            }
            copy.remove("animationKeys")
            rows.put(copy)
        }
        state.put("layers",rows).put("animation",ArtJsonCopy.objectValue(settings(original)).put("current",time))
            .put("selection",JSONObject.NULL).put("shapeSelection",JSONObject.NULL)
        return JSONObject().put("id",snapshot.getString("id")).put("revision",snapshot.getInt("revision")).put("state",state)
    }
}
