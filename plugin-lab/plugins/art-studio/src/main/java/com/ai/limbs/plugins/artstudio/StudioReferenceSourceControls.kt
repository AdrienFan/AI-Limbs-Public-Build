package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioReferenceSourceControls(settings:JSONObject,enabled:Boolean,onSettings:(JSONObject)->Unit) {
    Text("取样来源")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("current" to "当前图层","visible" to "全部可见图层","labels" to "标签图层").forEach {(id,label)->
            FilterChip(selected=settings.getString("reference")==id,enabled=enabled,
                onClick={onSettings(JSONObject(settings.toString()).put("reference",id))},label={Text(label)})
        }
    }
    if(settings.getString("reference")=="labels") {
        val selected=ArtLayerLabels.parse(settings.getJSONArray("colorLabels"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ArtLayerLabels.names.forEach {(id,label)->FilterChip(selected=id in selected,enabled=enabled,onClick={
                val next=selected.toMutableSet()
                if(id in next) {if(next.size>1)next.remove(id)} else next.add(id)
                onSettings(JSONObject(settings.toString()).put("colorLabels",JSONArray(next.sorted())))
            },label={Text(label)})}
        }
    }
}
