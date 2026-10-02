package com.ai.limbs.plugins.artstudio

import android.graphics.BlendMode
import android.graphics.Paint
import org.json.JSONObject

/** Source overlays blend against raw target-layer pixels, then the layer itself is composited. */
internal object ArtPixelBlend {
    val names=linkedMapOf("normal" to "正常","multiply" to "正片叠底","screen" to "滤色","overlay" to "叠加",
        "darken" to "变暗","lighten" to "变亮","add" to "相加","difference" to "差值","exclusion" to "排除",
        "hard_light" to "强光","soft_light" to "柔光","color_dodge" to "颜色减淡","color_burn" to "颜色加深",
        "hue" to "色相","saturation" to "饱和度","color" to "颜色","luminosity" to "明度")
    fun validate(id:String):String {require(id in names) {"不支持的填充混合模式：$id"};return id}
    fun paint(event:JSONObject)=Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Older pixel receipts have no blend field; their defined schema is normal source-over.
        val id=validate(event.optString("blend","normal"))
        require(event.getString("kind")!="erase" || id=="normal") {"擦除记录不能使用颜色混合模式"}
        blendMode=if(event.getString("kind")=="erase")BlendMode.DST_OUT else when(id) {
            "normal"->BlendMode.SRC_OVER;"multiply"->BlendMode.MULTIPLY;"screen"->BlendMode.SCREEN
            "overlay"->BlendMode.OVERLAY;"darken"->BlendMode.DARKEN;"lighten"->BlendMode.LIGHTEN
            "add"->BlendMode.PLUS;"difference"->BlendMode.DIFFERENCE;"exclusion"->BlendMode.EXCLUSION
            "hard_light"->BlendMode.HARD_LIGHT;"soft_light"->BlendMode.SOFT_LIGHT
            "color_dodge"->BlendMode.COLOR_DODGE;"color_burn"->BlendMode.COLOR_BURN
            "hue"->BlendMode.HUE;"saturation"->BlendMode.SATURATION;"color"->BlendMode.COLOR;"luminosity"->BlendMode.LUMINOSITY
            else->error("未映射的混合模式")
        }
    }
}
