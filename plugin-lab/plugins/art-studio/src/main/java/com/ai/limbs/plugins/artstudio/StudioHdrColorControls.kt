package com.ai.limbs.plugins.artstudio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray

@Composable
internal fun StudioHdrColorControls(label:String,value:JSONArray?,enabled:Boolean,onValue:(JSONArray?)->Unit) {
    var draft by remember(value?.toString()) {mutableStateOf(value?.toString() ?: "[1,1,1,1]")}
    FilterChip(selected=value!=null,enabled=enabled,onClick={onValue(if(value==null)JSONArray(draft) else null)},label={Text(label)})
    if(value!=null) {
        var error by remember {mutableStateOf("")}
        OutlinedTextField(draft,{draft=it},enabled=enabled,label={Text("线性 R,G,B,A，例如 [4,2,1,1]")},isError=error.isNotEmpty(),singleLine=true)
        TextButton(enabled=enabled,onClick={
            try {val array=JSONArray(draft);ArtFloatPixels.color(array);error="";onValue(array)}
            catch(e:Exception) {android.util.Log.w("ArtStudio","Invalid HDR color",e);error=e.message.orEmpty()}
        }) {Text("应用HDR颜色")}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        Text("线性RGB可为负或大于1，alpha为0–1。颜色按钮显示sRGB示意；实际填色保留浮点值。")
    }
}
