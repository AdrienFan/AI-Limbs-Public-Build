package com.ai.limbs.plugins.artstudio

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Stable plugin-owned labels; old projects without the property are explicitly unlabeled (0). */
internal object ArtLayerLabels {
    val names=linkedMapOf(0 to "无标签",1 to "蓝色",2 to "绿色",3 to "黄色",4 to "橙色",5 to "红色",6 to "紫色",7 to "灰色",8 to "棕色")
    val colors=mapOf(0 to Color.GRAY,1 to Color.rgb(65,140,240),2 to Color.rgb(65,170,100),3 to Color.rgb(220,190,45),
        4 to Color.rgb(240,140,45),5 to Color.rgb(220,70,70),6 to Color.rgb(160,100,220),7 to Color.rgb(145,145,145),8 to Color.rgb(150,105,65))
    fun value(layer:JSONObject):Int {
        if(!layer.has("colorLabel"))return 0
        val raw=layer.get("colorLabel");require(raw is Number&&raw.toDouble().isFinite()&&raw.toDouble()==raw.toInt().toDouble()&&raw.toInt() in names) {"图层颜色标签需要0–8的整数"}
        return raw.toInt()
    }
    fun parse(raw:JSONArray):Set<Int> {
        require(raw.length() in 1..9) {"请至少选择一个颜色标签"}
        val result=linkedSetOf<Int>()
        for(i in 0 until raw.length()) {
            val item=raw.get(i);require(item is Number&&item.toDouble().isFinite()&&item.toDouble()==item.toInt().toDouble()&&item.toInt() in names) {"参考标签需要0–8整数"}
            require(result.add(item.toInt())) {"参考标签不能重复"}
        };return result
    }
    fun referenceIds(state:JSONObject,labels:Set<Int>):Set<String> {
        val all=ArtMenuOperations.layers(state);val included=linkedSetOf<String>()
        fun visit(parent:String,inherited:Boolean,depth:Int) {
            require(depth<=all.size) {"图层组循环引用"}
            all.filter {it.optString("parentId")==parent}.forEach {layer->
                if(!layer.getBoolean("visible"))return@forEach
                val label=value(layer)
                if(layer.getString("kind")=="group")visit(layer.getString("id"),inherited||(label!=0&&label in labels),depth+1)
                else if(inherited||label in labels)included.add(layer.getString("id"))
            }
        }
        visit("",false,0);require(included.isNotEmpty()) {"没有匹配标签的可见内容图层；请在图层属性中设置标签"}
        val content=included.toList()
        for(id in content) {
            var layer=all.first {it.getString("id")==id};val seen=mutableSetOf(id)
            while(layer.optString("parentId").isNotBlank()) {
                val parent=layer.getString("parentId");require(seen.add(parent)) {"图层组循环引用"}
                layer=all.first {it.getString("id")==parent};included.add(parent)
            }
        };return included
    }
    fun info()=JSONObject().put("labels",JSONObject(names.mapKeys {it.key.toString()}))
        .put("assignment","layer.properties({id:LAYER_ID,colorLabel:1,expectedRevision:REV}); layer.list returns stored colorLabel; missing means 0.")
        .put("reference","reference=labels,colorLabels=[1,2]. Only visible matching content layers; matching nonzero group includes its visible descendants. Unmatched ancestor groups retain transforms, opacity and blend. Label0 selects unlabeled content, not an entire unlabeled group. Empty result is an error.")
}
