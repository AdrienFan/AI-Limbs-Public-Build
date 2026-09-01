package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.SharedPreferences

internal fun pluginCenterPreferences(
    context: Context,
    name: String,
    legacyName: String
): SharedPreferences {
    val appContext = context.applicationContext
    val current = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    if (current.all.isNotEmpty()) return current

    val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
    if (legacy.all.isEmpty()) return current

    val editor = current.edit()
    legacy.all.forEach { (key, value) ->
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    editor.commit()
    return current
}
