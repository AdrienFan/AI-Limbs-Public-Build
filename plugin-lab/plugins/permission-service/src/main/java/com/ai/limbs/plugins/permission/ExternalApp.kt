package com.ai.limbs.plugins.permission

import org.json.JSONArray
import org.json.JSONObject

internal data class ExternalApp(
    val uid: Int,
    val packageName: String,
    val packages: List<String>,
    val identity: String,
    val decision: String,
    val connected: Boolean,
    val pending: Int,
    val delivery: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uid", uid).put("package_name", packageName).put("packages", JSONArray(packages))
        .put("identity", identity).put("decision", decision).put("connected", connected)
        .put("pending", pending).put("delivery", delivery)

    companion object {
        fun fromJson(value: JSONObject): ExternalApp {
            val packages = value.getJSONArray("packages")
            return ExternalApp(
                value.getInt("uid"), value.getString("package_name"),
                List(packages.length()) { packages.getString(it) }, value.getString("identity"),
                value.getString("decision"), value.getBoolean("connected"), value.getInt("pending"),
                value.getString("delivery")
            )
        }
    }
}
