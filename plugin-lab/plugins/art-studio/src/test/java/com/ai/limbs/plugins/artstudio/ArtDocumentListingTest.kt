package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtDocumentListingTest {
    private fun event(id: String, type: String, parameters: JSONObject = JSONObject()) =
        JSONObject().put("id", id).put("type", type).put("parameters", parameters)

    private fun document(vararg events: JSONObject) = JSONObject().put("id", "document")
        .put("base", JSONObject().put("name", "original").put("width", 600).put("height", 400))
        .put("operations", JSONArray(events.toList()))

    private fun target(id: String) = JSONObject().put("targetId", id)
    private fun dimensions(width: Int, height: Int) = JSONObject().put("width", width).put("height", height)

    @Test fun selectiveUndoAndRestoreUseAppliedCanvasMetadata() {
        val doc = document(
            event("name", "DOCUMENT_RENAME", JSONObject().put("name", "  renamed  ")),
            event("crop", "CROP", dimensions(300, 200)),
            event("size", "CANVAS_RESIZE", dimensions(700, 500)),
            event("undo-size", "REVERT", target("size")),
            event("undo-name", "REVERT", target("name")),
            event("restore-name", "RESTORE", target("name")))
        val result = ArtDocumentListing.read(doc)
        assertEquals("renamed", result.getString("name"))
        assertEquals(300, result.getInt("width"))
        assertEquals(200, result.getInt("height"))
        doc.getJSONArray("operations").put(event("restore-size", "RESTORE", target("size")))
        assertEquals(700, ArtDocumentListing.read(doc).getInt("width"))
    }

    @Test fun laterBranchDoesNotReapplyUndoneRenameOrDimensions() {
        val doc = document(
            event("name", "DOCUMENT_RENAME", JSONObject().put("name", "abandoned")),
            event("size", "CANVAS_RESIZE", dimensions(1200, 900)),
            event("undo-size", "REVERT", target("size")),
            event("undo-name", "REVERT", target("name")),
            event("branch", "STROKE_ADD"))
        val result = ArtDocumentListing.read(doc)
        assertEquals("original", result.getString("name"))
        assertEquals(600, result.getInt("width"))
        assertEquals(400, result.getInt("height"))
    }

    @Test fun listingDoesNotReadGeometryPayloadOrMutateCommittedDocument() {
        // A geometry replay would require points/brush/layers/animation cels. A listing needs none.
        val doc = document(event("ink", "STROKE_ADD"), event("shape", "SHAPE_CREATE"),
            event("key", "ANIMATION_KEY"), event("text", "TEXT_CREATE"))
        val before = doc.toString()
        val result = ArtDocumentListing.read(doc)
        assertEquals("document", result.getString("id"))
        assertEquals(4, result.length())
        assertEquals(before, doc.toString())
    }

    @Test fun legacyUnnamedBaseKeepsPublishedDefaultName() {
        val doc = document()
        doc.getJSONObject("base").remove("name")
        assertEquals("未命名工程", ArtDocumentListing.read(doc).getString("name"))
    }
}
