package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Named selection layers are project data, never part of the artwork compositor. */
internal object ArtSelectionMasks {
    fun validate(state:JSONObject) {
        for(mask in ArtMenuOperations.layers(state).filter {it.getString("kind")=="selection_mask"}) {
            require(mask.getString("asset").isBlank() && mask.getJSONArray("strokes").length()==0 && !mask.has("animationKeys"))
            ArtSelection.validate(mask.getJSONObject("selectionMask"))
        }
    }
    fun edit(state:JSONObject,p:JSONObject) {
        val all=ArtMenuOperations.layers(state);val action=p.getString("action")
        if(action=="save") {
            val selection=state.optJSONObject("selection") ?: error("请先建立选区")
            ArtSelection.validate(selection)
            val id=p.getString("id");require(all.none {it.getString("id")==id})
            val name=p.optString("name","选区蒙版").trim();require(name.length in 1..100)
            val layer=JSONObject().put("id",id).put("name",name).put("kind","selection_mask")
                .put("selectionMask",JSONObject(selection.toString())).put("parentId","").put("visible",true).put("locked",false)
                .put("opacity",1.0).put("blend","normal").put("x",0.0).put("y",0.0).put("scale",1.0).put("rotation",0.0)
                .put("asset","").put("strokes",JSONArray())
            state.put("layers",JSONArray(all+layer));return
        }
        val mask=all.firstOrNull {it.getString("id")==p.getString("id") && it.getString("kind")=="selection_mask"} ?: error("选区蒙版不存在")
        if(action=="activate") {val selection=mask.getJSONObject("selectionMask");ArtSelection.validate(selection)
            state.put("selection",ArtSelectionTransform.transformed(selection,ArtShapes.layerMatrix(state,mask),state.getInt("width"),state.getInt("height")))
        } else {
            require(!ArtMenuOperations.isLocked(state,mask)) {"选区蒙版或父组已锁定"}
            when(action) {
                "replace"->{val selection=state.optJSONObject("selection") ?: error("当前没有选区");ArtSelection.validate(selection)
                    mask.put("selectionMask",ArtSelectionTransform.transformed(selection,ArtPixelCoordinates.inverse(state,mask),state.getInt("width"),state.getInt("height"),clip=false))}
                "delete"->{state.put("layers",JSONArray(all.filter {it!==mask}));if(state.optString("selectedLayerId")==mask.getString("id"))state.put("selectedLayerId",all.firstOrNull {it!==mask}?.getString("id") ?: "")}
                else->error("未知选区蒙版操作")
            }
        }
    }
    fun list(state:JSONObject)=JSONArray(ArtMenuOperations.layers(state).filter {it.getString("kind")=="selection_mask"}.map {
        JSONObject().put("id",it.getString("id")).put("name",it.getString("name")).put("locked",it.getBoolean("locked"))
    })
}
