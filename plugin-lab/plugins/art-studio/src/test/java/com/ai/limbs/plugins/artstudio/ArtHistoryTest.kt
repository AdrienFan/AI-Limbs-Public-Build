package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtHistoryTest {
    private fun event(id:String,type:String,p:JSONObject=JSONObject())=JSONObject()
        .put("id",id).put("type",type).put("parameters",p).put("actor","LANER").put("timestamp",1000L)
    private fun doc(events:List<JSONObject>)=JSONObject().put("id","doc").put("createdBy","AWEI")
        .put("operations",JSONArray(events))
    private fun change(id:String,type:String,target:String)=event(id,type,JSONObject().put("targetId",target))
    private fun ids(result:JSONObject,key:String="timeline")=result.getJSONArray(key).let {rows->
        (0 until rows.length()).map {rows.getJSONObject(it).getString("id")}
    }
    @Test fun compactHistoryRetainsCountsAndUndoLabelsAcrossBranches() {
        val source=doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),
            change("undo","REVERT","b"),event("c","LAYER_CREATE"),change("undo2","REVERT","c")))
        val full=ArtHistory.describe(source)
        val compact=ArtHistory.describe(source,details=false)
        for(key in listOf("documentId","revision","position","canUndo","canRedo","undoLabel","redoLabel","historyStats"))
            assertEquals(full.get(key).toString(),compact.get(key).toString())
        assertFalse(compact.has("timeline"));assertFalse(compact.has("otherBranches"))
    }

    @Test fun selectiveUndoAndRestoreKeepReachableOrder() {
        val events=listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),event("c","LAYER_CREATE"),
            change("u","REVERT","b"),change("r","RESTORE","b"))
        val result=ArtHistory.describe(doc(events))
        assertEquals(listOf("","a","c","b"),ids(result))
        assertEquals("重新应用 · 添加图层",result.getJSONArray("timeline").getJSONObject(3).getString("label"))
        assertEquals(3,result.getInt("position"))
        assertEquals(5,result.getInt("revision"))
        assertEquals(0,result.getJSONArray("otherBranches").length())
    }
    @Test fun newBranchRetainsOriginalEventsAndSeparatesNonReachableStates() {
        val original=doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),
            change("u","REVERT","b"),event("c","LAYER_CREATE")))
        val before=original.toString()
        val result=ArtHistory.describe(original)
        assertEquals(listOf("","a","c"),ids(result))
        assertEquals(listOf("b"),ids(result,"otherBranches"))
        assertEquals(1,result.getJSONObject("historyStats").getInt("otherBranchSteps"))
        assertFalse(result.getBoolean("canRedo"))
        assertEquals(before,original.toString())
    }
    @Test fun undoKeepsFutureStepsAndInitialStateReachable() {
        val result=ArtHistory.describe(doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),
            change("u1","REVERT","b"),change("u2","REVERT","a"))))
        assertEquals(listOf("","a","b"),ids(result))
        assertEquals(0,result.getInt("position"))
        assertFalse(result.getBoolean("canUndo"))
        assertTrue(result.getBoolean("canRedo"))
    }
    @Test fun vectorToolOriginAndBatchContentsAreDistinguished() {
        val shape=JSONObject().put("kind","path").put("points",JSONArray().put(JSONArray().put(0).put(0)))
        val calligraphy=event("a","SHAPE_CREATE",JSONObject().put("historyTool","vector_calligraphy").put("shape",shape))
        val bezier=event("b","SHAPE_CREATE",JSONObject().put("historyTool","vector_bezier").put("shape",shape))
        val legacy=event("c","SHAPE_CREATE",JSONObject().put("shape",shape))
        val layers=JSONArray().put(JSONObject().put("name","月光").put("shapes",JSONArray().put(shape).put(shape)))
        val svg=event("d","SVG_APPLY",JSONObject().put("layers",layers))
        val result=ArtHistory.describe(doc(listOf(calligraphy,bezier,legacy,svg)))
        val rows=result.getJSONArray("timeline")
        assertEquals("矢量书法笔",rows.getJSONObject(1).getString("label"))
        assertEquals("vector_calligraphy",rows.getJSONObject(1).getString("tool"))
        assertEquals("可编辑贝塞尔路径",rows.getJSONObject(2).getString("label"))
        assertEquals("添加矢量路径",rows.getJSONObject(3).getString("label"))
        assertTrue(rows.getJSONObject(4).getString("summary").contains("2个形状"))
        assertTrue(rows.getJSONObject(4).getString("summary").contains("月光"))
        assertFalse(rows.getJSONObject(4).has("layers"))
    }
    @Test fun largeMixedToolHistoryHasNoTruncationAndNeedsNoDocumentGeometry() {
        val types=listOf("STROKE_ERASE","TEXT_UPDATE","SELECTION_CLEAR","LAYER_VISIBLE",
            "REFERENCE_TRANSFORM","ASSISTANT_UPDATE","COLORIZE_OUTPUT","TRANSFORM_AFFINE",
            "MOVE_LAYER","CANVAS_RESIZE","SHAPE_STYLE","MENU_LAYER_CHANGE","VECTOR_LAYER_CREATE")
        val events=List(3000) {i->event("e$i",types[i%types.size],JSONObject().put("label","滤镜处理"))}
        val result=ArtHistory.describe(doc(events))
        assertEquals(3001,result.getJSONArray("timeline").length())
        assertEquals(3000,result.getInt("position"))
        assertEquals(3000,result.getJSONObject("historyStats").getInt("editCount"))
        val rows=result.getJSONArray("timeline")
        assertEquals("栅格",rows.getJSONObject(1).getString("category"))
        assertEquals("文字",rows.getJSONObject(2).getString("category"))
        assertEquals("滤镜处理",rows.getJSONObject(12).getString("label"))
        assertEquals("图层",rows.getJSONObject(13).getString("category"))
        assertEquals("e2999",rows.getJSONObject(3000).getString("id"))
    }

    @Test fun pagesPreserveGlobalOrderIndexesAndSelectiveRestoreLabels() {
        val source=doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),event("c","LAYER_CREATE"),
            change("u","REVERT","b"),change("r","RESTORE","b")))
        val full=ArtHistory.describe(source)
        val assembled=mutableListOf<String>()
        var offset=0
        while(true) {
            val page=ArtHistory.describe(source,query=JSONObject().put("offset",offset).put("limit",2))
            val rows=page.getJSONArray("timeline")
            assertEquals(4,page.getInt("total"))
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                assertEquals(offset+i,row.getInt("index"))
                assertEquals(full.getJSONArray("timeline").getJSONObject(offset+i).getString("label"),row.getString("label"))
                assembled.add(row.getString("id"))
            }
            if(page.getBoolean("complete")) {assertTrue(page.isNull("nextOffset"));break}
            offset=page.getInt("nextOffset")
        }
        assertEquals(ids(full),assembled)
        val empty=ArtHistory.describe(source,query=JSONObject().put("offset",4).put("limit",2))
        assertEquals(0,empty.getJSONArray("timeline").length());assertTrue(empty.getBoolean("complete"))
        try {ArtHistory.describe(source,query=JSONObject().put("offset",5));fail("Must reject out-of-range offset")}
        catch(expected:IllegalArgumentException) { }
        try {ArtHistory.describe(source,query=JSONObject().put("limit",101));fail("Must reject excessive page")}
        catch(expected:IllegalArgumentException) { }
    }

    @Test fun individualReadsDistinguishFutureOtherBranchAndInitialState() {
        val future=doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),change("u","REVERT","b")))
        val row=ArtHistory.describe(future,query=JSONObject().put("id","b")).getJSONObject("entry")
        assertTrue(row.getBoolean("canGoto"));assertFalse(row.getBoolean("applied"))
        val source=doc(listOf(event("a","LAYER_CREATE"),event("b","LAYER_CREATE"),change("u","REVERT","b"),event("c","LAYER_CREATE")))
        val other=ArtHistory.describe(source,query=JSONObject().put("id","b").put("compact",true))
        assertEquals("otherBranches",other.getString("branch"))
        assertFalse(other.getJSONObject("entry").getBoolean("canGoto"))
        assertFalse(other.has("timeline"));assertFalse(other.has("otherBranches"))
        assertFalse(other.getJSONObject("entry").has("summary"))
        val page=ArtHistory.describe(source,query=JSONObject().put("branch","otherBranches").put("limit",1))
        assertEquals(listOf("b"),ids(page,"otherBranches"));assertEquals(1,page.getInt("total"))
        val initial=ArtHistory.describe(source,query=JSONObject().put("id",""))
        assertEquals(0,initial.getJSONObject("entry").getInt("index"))
        assertEquals("初始画布",initial.getJSONObject("entry").getString("label"))
        try {ArtHistory.describe(source,query=JSONObject().put("id","missing"));fail("Must reject unknown ID")}
        catch(expected:IllegalArgumentException) { }
    }

    @Test fun selectedProjectionDoesNotReadUnrequestedDrawingPayloads() {
        // Deliberately omit another row's shape data. A directed read must not build that summary.
        val source=doc(listOf(event("unrequested","SHAPE_CREATE"),event("wanted","LAYER_CREATE")))
        val result=ArtHistory.describe(source,query=JSONObject().put("id","wanted").put("compact",true))
        assertEquals("wanted",result.getJSONObject("entry").getString("id"))
        assertFalse(result.has("timeline"));assertFalse(result.getJSONObject("entry").has("actor"))
    }

}
