package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Independent JSON trees without encoding/decoding every numeric coordinate and inactive cel. */
internal object ArtJsonCopy {
    fun objectValue(source:JSONObject):JSONObject = JSONObject().apply {
        source.keys().forEach {key -> put(key,value(source.get(key)))}
    }
    fun arrayValue(source:JSONArray):JSONArray = JSONArray().apply {
        for(index in 0 until source.length())put(value(source.opt(index)))
    }
    fun value(source:Any?):Any = when(source) {
        is JSONObject -> objectValue(source)
        is JSONArray -> arrayValue(source)
        is String -> source
        is Boolean -> source
        is Number -> {
            require(source !is Double || source.isFinite()) {"JSON数字须为有限值"}
            require(source !is Float || source.isFinite()) {"JSON数字须为有限值"}
            source
        }
        null -> JSONObject.NULL
        else -> {require(source===JSONObject.NULL) {"非JSON数据不能复制"};JSONObject.NULL}
    }
}
