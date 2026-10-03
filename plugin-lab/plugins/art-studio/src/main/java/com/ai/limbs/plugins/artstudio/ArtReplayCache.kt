package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import java.security.MessageDigest

/** One derived state. Inputs remain authoritative; callers always own a separate mutable tree. */
internal class ArtReplayCache {
    private data class Entry(val source:String,val identity:String,val events:List<String>,val state:String)
    private var retained:Entry?=null

    fun read(doc:JSONObject, rebuild:(JSONObject)->JSONObject,
        advance:(JSONObject,List<JSONObject>)->JSONObject):JSONObject {
        val encodedDocument=doc.toString()
        // One whole-input digest avoids reserializing and hashing every cel-bearing event on
        // unchanged reads. Never trust just revision/length/mtime: another process can replace data.
        val source=ArtDocumentDigest.of(encodedDocument)
        val old=retained
        if(old!=null && old.source==source) return JSONObject(old.state)
        val identity=doc.getString("id")+":"+hash(doc.getJSONObject("base").toString())
        val operations=doc.getJSONArray("operations")
        val events=(0 until operations.length()).map {operations.getJSONObject(it)}
        val signatures=events.map {hash(it.toString())}
        if(old!=null && old.identity==identity && old.events==signatures) {
            retained=old.copy(source=source)
            return JSONObject(old.state)
        }
        val extends=old!=null && old.identity==identity && signatures.size>old.events.size &&
            signatures.take(old.events.size)==old.events
        val added=if(extends)events.drop(requireNotNull(old).events.size) else emptyList()
        val appendable=extends && added.none {it.getString("type") in setOf("REVERT","RESTORE")} &&
            events.map {it.getString("id")}.toSet().size==events.size
        // A selective undo/redo, replacement, import or switch requires its own complete replay.
        // Only a cryptographically identical prefix permits incremental application.
        val state=if(appendable)advance(JSONObject(requireNotNull(old).state),added) else rebuild(doc)
        retained=Entry(source,identity,signatures,state.toString())
        return state
    }

    private fun hash(value:String):String=ArtDocumentDigest.of(value)
}

/** Hex encoding without per-byte Formatter allocation; persisted SHA-256 stays identical. */
internal object ArtDocumentDigest {
    private const val HEX="0123456789abcdef"
    fun of(value:String):String {
        val bytes=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val chars=CharArray(bytes.size*2)
        for(i in bytes.indices) {
            val byte=bytes[i].toInt() and 255
            chars[i*2]=HEX[byte ushr 4];chars[i*2+1]=HEX[byte and 15]
        }
        return String(chars)
    }
}


/** A bounded compact projection, independent of full-state replay caching. */
internal class ArtSummaryCache {
    data class Summary(val value:JSONObject,val documentDigest:String)
    private data class Entry(val documentId:String,val sourceDigest:String,
        val documentDigest:String,val encodedSummary:String)
    private var retained:Entry?=null

    fun read(documentId:String,source:String,build:(JSONObject)->JSONObject):Summary {
        // Compare complete content, so same-length replacements or external writers cannot
        // reuse stale labels/counts. Metadata-only reads need not parse all cels on a cache hit.
        val sourceDigest=ArtDocumentDigest.of(source)
        val old=retained
        if(old!=null && old.documentId==documentId && old.sourceDigest==sourceDigest)
            return Summary(JSONObject(old.encodedSummary),old.documentDigest)
        val doc=JSONObject(source)
        require(doc.getString("id")==documentId) {"草稿工程编号不匹配"}
        val summary=build(doc)
        // Projection/replay can normalize old tool defaults. Match snapshot dirty semantics
        // using the resulting canonical document, rather than the unnormalized file text.
        val documentDigest=ArtDocumentDigest.of(doc.toString())
        retained=Entry(documentId,sourceDigest,documentDigest,summary.toString())
        return Summary(summary,documentDigest)
    }
}
