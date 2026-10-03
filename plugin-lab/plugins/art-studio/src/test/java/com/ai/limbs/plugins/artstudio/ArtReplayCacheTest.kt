package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtReplayCacheTest {
    private fun document(id:String="one")=JSONObject().put("id",id)
        .put("base",JSONObject().put("value",0)).put("operations",JSONArray())
    private fun add(doc:JSONObject,id:String,amount:Int)=doc.apply {
        getJSONArray("operations").put(JSONObject().put("id",id).put("type","ADD")
            .put("parameters",JSONObject().put("amount",amount)))
    }
    private fun complete(doc:JSONObject):JSONObject {
        val disabled=mutableSetOf<String>()
        val events=doc.getJSONArray("operations")
        for(i in 0 until events.length()) {
            val op=events.getJSONObject(i)
            when(op.getString("type")) {
                "REVERT" -> disabled.add(op.getJSONObject("parameters").getString("targetId"))
                "RESTORE" -> disabled.remove(op.getJSONObject("parameters").getString("targetId"))
            }
        }
        val state=JSONObject(doc.getJSONObject("base").toString())
        for(i in 0 until events.length()) {
            val op=events.getJSONObject(i)
            if(op.getString("type")=="ADD" && op.getString("id") !in disabled) apply(state,listOf(op))
        }
        return state
    }
    private fun apply(state:JSONObject,events:List<JSONObject>):JSONObject=state.apply {
        for(op in events)put("value",getInt("value")+op.getJSONObject("parameters").getInt("amount"))
    }

    @Test fun longAppendSequenceMatchesIndependentReplayAndDoesNotRebuild() {
        val cache=ArtReplayCache();val doc=document();var rebuilds=0;var advanced=0
        fun read()=cache.read(doc,{rebuilds++;complete(it)},{state,events->advanced+=events.size;apply(state,events)})
        read()
        repeat(100) {i->add(doc,"event-$i",i+1);assertEquals(complete(doc).toString(),read().toString())}
        assertEquals(1,rebuilds);assertEquals(100,advanced)
        read().put("value",-1)
        assertEquals(5050,read().getInt("value"))
    }

    @Test fun selectiveUndoRedoAndSameLengthReplacementRebuildAuthoritativeState() {
        val cache=ArtReplayCache();val doc=add(add(document(),"a",4),"b",9)
        fun read()=cache.read(doc,::complete,::apply)
        assertEquals(13,read().getInt("value"))
        doc.getJSONArray("operations").put(JSONObject().put("id","undo").put("type","REVERT")
            .put("parameters",JSONObject().put("targetId","a")))
        assertEquals(9,read().getInt("value"))
        add(doc,"c",2);assertEquals(11,read().getInt("value"))
        doc.getJSONArray("operations").put(JSONObject().put("id","redo").put("type","RESTORE")
            .put("parameters",JSONObject().put("targetId","a")))
        assertEquals(15,read().getInt("value"))
        doc.getJSONArray("operations").getJSONObject(1).getJSONObject("parameters").put("amount",20)
        assertEquals(26,read().getInt("value"))
        doc.getJSONObject("base").put("value",100)
        assertEquals(126,read().getInt("value"))
        assertEquals(0,cache.read(document("two"),::complete,::apply).getInt("value"))
    }

    @Test fun failedAdvanceCannotPoisonPreviousCheckpoint() {
        val cache=ArtReplayCache();val doc=add(document(),"a",3)
        cache.read(doc,::complete,::apply)
        add(doc,"b",5)
        try {
            cache.read(doc,::complete) {state,events->apply(state,events);error("validation failed")}
            fail("Must propagate validation failure")
        } catch(expected:IllegalStateException) {assertEquals("validation failed",expected.message)}
        assertEquals(8,cache.read(doc,::complete,::apply).getInt("value"))
    }
}
