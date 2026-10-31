package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioFilterPalette(key:String,palettes:JSONArray,onChange:(String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        TextButton(onClick={expanded=true}) {Text("采用共享调色板快照")}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=240.dp)) {
            if(palettes.length()==0)DropdownMenuItem(text={Text("尚无共享调色板，可直接编辑颜色JSON")},onClick={},enabled=false)
            for(i in 0 until palettes.length()) {
                val palette=palettes.getJSONObject(i);val colors=palette.getJSONArray("colors")
                val enabled=colors.length() in 2..(if(key=="ramps")16 else 512)
                DropdownMenuItem(text={Text(palette.getString("name")+" · ${colors.length()}色")},enabled=enabled,onClick={
                    expanded=false
                    if(key=="palette")onChange(colors.toString()) else {
                        val ramps=JSONArray()
                        for(start in 0 until colors.length() step 4)ramps.put(JSONArray().apply {
                            for(j in 0..3)put(if(start+j<colors.length())colors.get(start+j) else JSONObject.NULL)
                        })
                        onChange(ramps.toString())
                    }
                })
            }
        }
    }
    if(key=="ramps")Text("按调色板顺序，每4色为一坡：明亮→浅色→基色→阴影；最多16个基色。")
    Text("采用后保存颜色值，不跟随共享调色板的后续修改。")
}

/** Snapshot selection is read-only; closing the dialog interrupts work and cannot commit an edit. */
@Composable
internal fun StudioFilterReference(layers:JSONArray,read:(String)->JSONObject,onChange:(JSONObject)->Boolean) {
    var expanded by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("选择参考根图层，或直接编辑下方统计值")}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Box {
        TextButton(enabled=!busy,onClick={expanded=true}) {Text(if(busy)"读取参考统计…" else "从图层读取参考颜色")}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=240.dp)) {
            for(i in 0 until layers.length()) {
                val layer=layers.getJSONObject(i)
                DropdownMenuItem(text={Text(layer.getString("name"))},onClick={
                    expanded=false;busy=true
                    val id=layer.getString("id")
                    scope.launch {
                        try {
                            val result=runInterruptible(Dispatchers.IO) {read(id)}
                            message=if(onChange(result.getJSONObject("referenceStats")))
                                "已固定参考：${result.getString("layerName")}；后续编辑不改变这份统计快照。"
                            else "参数已变化，本次参考结果未覆盖输入。"
                        } catch(e:java.util.concurrent.CancellationException) {throw e}
                        catch(e:Exception) {android.util.Log.w("ArtStudioFilter","Reference statistics failed",e);message=e.message ?: "参考统计失败"}
                        finally {busy=false}
                    }
                })
            }
        }
    }
    Text(message)
}
