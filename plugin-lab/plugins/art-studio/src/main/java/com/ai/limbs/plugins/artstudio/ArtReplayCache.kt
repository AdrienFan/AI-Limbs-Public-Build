package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import java.security.MessageDigest

/** One derived state. Inputs remain authoritative; callers always own a separate mutable tree. */
internal class ArtReplayCache {
    private data class Entry(val identity:String,val events:List<String>,val state:String)
    private var retained:Entry?=null

    fun read(doc:JSONObject, rebuild:(JSONObject)->JSONObject,
        advance:(JSONObject,List<JSONObject>)->JSONObject):JSONObject {
        val identity=doc.getString("id")+":"+hash(doc.getJSONObject("base").toString())
        val operations=doc.getJSONArray("operations")
        val events=(0 until operations.length()).map {operations.getJSONObject(it)}
        val signatures=events.map {hash(it.toString())}
        val old=retained
        if(old!=null && old.identity==identity && old.events==signatures) return JSONObject(old.state)
        val extends=old!=null && old.identity==identity && signatures.size>old.events.size &&
            signatures.take(old.events.size)==old.events
        val added=if(extends)events.drop(requireNotNull(old).events.size) else emptyList()
        val appendable=extends && added.none {it.getString("type") in setOf("REVERT","RESTORE")} &&
            events.map {it.getString("id")}.toSet().size==events.size
        // A selective undo/redo, replacement, import or switch requires its own complete replay.
        // Only a cryptographically identical prefix permits incremental application.
        val state=if(appendable)advance(JSONObject(requireNotNull(old).state),added) else rebuild(doc)
        retained=Entry(identity,signatures,state.toString())
        return state
    }

    private fun hash(value:String):String=MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
}
