package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioSmartPatchOptions(settings: JSONObject,busy: Boolean,onSettings: (JSONObject)->Unit) {
    fun set(key: String,value: Int) {onSettings(JSONObject(settings.toString()).put(key,value))}
    Column {
        Text("涂抹需要移除的区域，粉色是临时蒙版；松手后自动修补。请把物体边缘也涂进去。")
        Text("笔径：${settings.getInt("width")} px")
        Slider(settings.getInt("width").toFloat(),{set("width",it.roundToInt())},
            enabled=!busy,valueRange=1f..256f)
        Text("补丁半径：${settings.getInt("patchRadius")} px")
        Slider(settings.getInt("patchRadius").toFloat(),{set("patchRadius",it.roundToInt())},
            enabled=!busy,valueRange=1f..8f,steps=6)
        Text("搜索半径：${settings.getInt("searchRadius")} px")
        Slider(settings.getInt("searchRadius").toFloat(),{set("searchRadius",it.roundToInt())},
            enabled=!busy,valueRange=16f..1024f)
        Text("精度：${settings.getInt("accuracy")}%")
        Slider(settings.getInt("accuracy").toFloat(),{set("accuracy",it.roundToInt())},
            enabled=!busy,valueRange=1f..100f)
        Text("边缘融合：${settings.getInt("feather")} px")
        Slider(settings.getInt("feather").toFloat(),{set("feather",it.roundToInt())},
            enabled=!busy,valueRange=0f..8f,steps=7)
        Text("金字塔层数：${if(settings.getInt("levels")==0)"自动" else settings.getInt("levels").toString()}")
        Slider(settings.getInt("levels").toFloat(),{set("levels",it.roundToInt())},enabled=!busy,valueRange=0f..6f,steps=5)
        Text("0按蒙版和有效纹理规划，1单尺度，2–6指定层数；粗层没有完整纹理时指定层数会被拒绝。")
        Text("细化间距：${if(settings.getInt("refinementStep")==0)"自动" else settings.getInt("refinementStep").toString()+" px"}")
        Slider(settings.getInt("refinementStep").toFloat(),{set("refinementStep",it.roundToInt())},enabled=!busy,valueRange=0f..64f,steps=63)
        Text("0按范围/精度选间距；1逐像素细化，更慢；较大值用稀疏匹配，每个原尺寸像素仍参与纹理投票。")
        var seed by remember(settings.getInt("seed")) {mutableStateOf(settings.getInt("seed").toString())}
        val seedValue=seed.toIntOrNull()?.takeIf {it>=0}
        OutlinedTextField(seed,{seed=it},enabled=!busy,singleLine=true,label={Text("随机种子 0–2147483647")},isError=seedValue==null)
        TextButton(enabled=!busy&&seedValue!=null&&seedValue!=settings.getInt("seed"),onClick={set("seed",requireNotNull(seedValue))}) {Text("应用种子")}
        Text("只修补当前未变换的根绘画／图像图层；选区限制写入范围。小补丁适合细纹理，大补丁适合较大的纹理。高精度更慢。")
        Text("附近没有合适纹理时会明确报错；复杂结构的结果仍需检查，可一次撤销。单次涂抹最多1048576像素，搜索外框最多8388608像素；还需通过内存及计算预算。大区域适合自动层数/间距，精度越高越慢。")
        ArtSmartPatch.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
    }
}
