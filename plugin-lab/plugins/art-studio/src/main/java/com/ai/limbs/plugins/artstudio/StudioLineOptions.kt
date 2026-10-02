package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StudioLineOptions(busy:Boolean,draft:Boolean,useSensors:Boolean,snap:Boolean,hold:Boolean,move:Boolean,step:Float,
    onSensors:(Boolean)->Unit,onSnap:(Boolean)->Unit,onHold:(Boolean)->Unit,onMove:(Boolean)->Unit,onStep:(Float)->Unit,onCommand:(String)->Unit) {
    Text("直线",style=MaterialTheme.typography.titleSmall)
    FilterChip(selected=useSensors,enabled=!busy&&!draft,onClick={onSensors(!useSensors)},label={Text("使用传感器")})
    FilterChip(selected=snap,enabled=!busy,onClick={onSnap(!snap)},label={Text("吸附直线尺规")})
    Text("角度约束优先于尺规；只吸附直尺、无限/平行尺、消失点。",style=MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf(0f,15f,30f,45f,90f).forEach {value -> FilterChip(selected=step==value,enabled=!busy,
            onClick={onStep(value)},label={Text(if(value==0f)"自由" else "${value.toInt()}°")})}
    }
    FilterChip(selected=hold,enabled=!busy,onClick={onHold(!hold)},label={Text("抬手暂存")})
    FilterChip(selected=move,enabled=!busy&&draft,onClick={onMove(!move)},label={Text("移动起点")})
    Text("拖动后默认抬手完成。需要调整时先开启暂存，再移动起点或修改终点，最后点完成。Shift：15°；起笔后按Alt拖动：平移；Enter完成，Esc取消。",style=MaterialTheme.typography.labelSmall)
    Row {
        TextButton(enabled=!busy&&draft,onClick={onCommand("finish")}) {Text("完成直线")}
        TextButton(enabled=draft,onClick={onCommand("cancel")}) {Text("取消")}
    }
    Text("笔刷共享笔尖、纹理和动态曲线；直线不使用加权平滑、稳定器或定时喷绘。",modifier=Modifier.padding(bottom=4.dp),style=MaterialTheme.typography.labelSmall)
}
