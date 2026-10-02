package com.ai.limbs.plugins.artstudio

import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioSoftSelectionControls(settings:JSONObject,enabled:Boolean,onSet:(String,Any)->Unit) {
    Text("抗锯齿：${(settings.getDouble("antialias")*100).roundToInt()}%")
    Slider(settings.getDouble("antialias").toFloat(),{onSet("antialias",it.toDouble())},enabled=enabled&&settings.getInt("feather")==0,valueRange=0f..1f)
    Text("扩展／收缩：${settings.getInt("expand")} px")
    Slider(settings.getInt("expand").toFloat(),{onSet("expand",it.roundToInt())},enabled=enabled,valueRange=-64f..64f)
    Text("羽化半径：${settings.getInt("feather")} px")
    Slider(settings.getInt("feather").toFloat(),{onSet("feather",it.roundToInt())},enabled=enabled,valueRange=0f..32f)
    Text("先扩展／收缩，再羽化，再组合；羽化大于0时不重复抗锯齿。软边使用0–255覆盖率，单次范围最多4194304像素。")
}
