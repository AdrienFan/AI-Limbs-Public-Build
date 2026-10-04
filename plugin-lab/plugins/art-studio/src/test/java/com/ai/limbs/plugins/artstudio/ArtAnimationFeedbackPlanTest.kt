package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtAnimationFeedbackPlanTest {
    @Test fun everyRequestedFrameIsIncludedOnceInTimeOrderAtTheMaximumBatchSize() {
        val frames=(0..31).map {it*3}.reversed()
        val plan=ArtAnimationFeedbackPlan.create(frames)
        assertEquals((0..31).map {it*3},plan.frames)
        assertEquals(frames.first(),93);assertEquals(4,plan.columns);assertEquals(8,plan.rows)
        val metadata=plan.metadata(JSONObject().put("id","committed-document").put("revision",12))
        assertEquals("committed-document",metadata.getString("documentId"));assertEquals(12,metadata.getInt("revision"))
        assertEquals(32,metadata.getJSONArray("frames").length())
        assertEquals(93,metadata.getJSONArray("frames").getInt(31))
    }

    @Test fun compactBatchReceiptPreservesTheOriginalThumbnailAndAddsTheSheetAsASecondImage() {
        val thumbnail=JSONObject().put("kind","thumbnail").put("revision",5)
        val current=JSONObject().put("type","image").put("data","CURRENT")
        val result=JSONObject().put("id","doc").put("revision",5).put("state",JSONObject())
            .put("operations",JSONArray()).put("thumbnail",thumbnail).put("mcp_content",JSONArray().put(current))
        val plan=ArtAnimationFeedbackPlan.create(listOf(22,0,55))
        val sheet=JSONObject().put("metadata",plan.metadata(result))
            .put("content",JSONObject().put("type","image").put("data","SHEET"))
        val receipt=ArtCapabilityReply.format(plan.append(result,sheet),"receipt")
        assertSame(thumbnail,receipt.getJSONObject("thumbnail"))
        assertEquals("CURRENT",receipt.getJSONArray("mcp_content").getJSONObject(0).getString("data"))
        assertEquals("SHEET",receipt.getJSONArray("mcp_content").getJSONObject(1).getString("data"))
        assertEquals(1,receipt.getJSONObject("animationFeedback").getInt("imageContentIndex"))
        assertEquals(listOf(0,22,55),(0..2).map {receipt.getJSONObject("animationFeedback").getJSONArray("frames").getInt(it)})
        assertFalse(receipt.has("state"));assertFalse(receipt.has("operations"))
    }

    @Test fun invalidOrOversizeFrameListsAreRejectedInsteadOfTruncated() {
        for(frames in listOf(emptyList(),listOf(1,1),listOf(-1),listOf(10000),(0..32).toList())) {
            try {ArtAnimationFeedbackPlan.create(frames);fail("Must reject invalid frame list")}
            catch(expected:IllegalArgumentException) {assertTrue(expected.message.orEmpty().isNotBlank())}
        }
    }
}
