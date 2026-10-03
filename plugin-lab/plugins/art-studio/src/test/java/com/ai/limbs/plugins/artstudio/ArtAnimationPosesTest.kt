package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source-level regression cases for cloud CI; no renderer calls. */
class ArtAnimationPosesTest {
    private fun layer() = JSONObject().put("id", "layer").put("kind", "vector").put("name", "person")
        .put("parentId", "").put("locked", false).put("visible", true).put("x", 0.0).put("y", 0.0)
        .put("scale", 1.0).put("rotation", 0.0).put("opacity", 1.0).put("strokes", JSONArray())
        .put("shapes", JSONArray().put(JSONObject().put("id", "umbrella").put("kind", "path")
            .put("locked", false).put("opacity", 1.0).put("visible", true).put("matrix", JSONArray(listOf(1,0,0,1,0,0)))))
    private fun parameters() = JSONObject().put("layerId", "layer").put("sourceFrame", 0)
        .put("poses", JSONArray().put(JSONObject().put("frame", 2).put("layer", JSONObject().put("x", 10)))
            .put(JSONObject().put("frame", 4).put("layer", JSONObject().put("x", 20)))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }
    @Test fun multiplePosesReuseOneBaseAndPreservePlayheadAndAnchor() {
        val layer = layer()
        val state = JSONObject().put("layers", JSONArray().put(layer)).put("animation", ArtAnimation.settings(JSONObject()).put("current", 3))
        val prepared = ArtAnimationPoses.prepare(layer, parameters())
        assertEquals(0.0, prepared.getJSONObject("baseCel").getDouble("x"), 0.0)
        ArtAnimation.edit(state, "ANIMATION_POSES", prepared)
        assertEquals(3, ArtAnimation.settings(state).getInt("current"))
        assertEquals(listOf(0,2,4), (0..2).map { ArtAnimation.keys(layer)!!.getJSONObject(it).getInt("time") })
        ArtAnimation.resolve(state, 4); assertEquals(20.0, layer.getDouble("x"), 0.0)
        ArtAnimation.resolve(state, 0); assertEquals(0.0, layer.getDouble("x"), 0.0)
        assertEquals(0.0, prepared.getJSONObject("baseCel").getDouble("x"), 0.0)
    }
    @Test fun collisionsRequireExplicitOverwriteAndUnrelatedKeysStayPut() {
        val layer = layer(); ArtAnimationPoses.install(layer, ArtAnimationPoses.prepare(layer, parameters()))
        val before = layer.toString()
        rejects { ArtAnimationPoses.install(layer, ArtAnimationPoses.prepare(layer, parameters())) }
        assertEquals(before, layer.toString())
        val replacement = parameters().put("overwrite", true)
        replacement.getJSONArray("poses").getJSONObject(0).getJSONObject("layer").put("x", 50)
        ArtAnimationPoses.install(layer, ArtAnimationPoses.prepare(layer, replacement))
        assertEquals(50.0, ArtAnimation.active(layer, 2)!!.getJSONObject("content").getDouble("x"), 0.0)
        assertEquals(20.0, ArtAnimation.active(layer, 4)!!.getJSONObject("content").getDouble("x"), 0.0)
    }
    @Test fun invalidRecipesAndLockedObjectsAreRejectedBeforeMutation() {
        val layer = layer(); val before = layer.toString()
        val duplicate = parameters(); duplicate.getJSONArray("poses").getJSONObject(1).put("frame", 2)
        rejects { ArtAnimationPoses.prepare(layer, duplicate) }
        val invalid = parameters(); invalid.getJSONArray("poses").getJSONObject(1).getJSONObject("layer").put("opacity", 2)
        rejects { ArtAnimationPoses.prepare(layer, invalid) }
        val matrix = parameters(); matrix.getJSONArray("poses").getJSONObject(0).getJSONObject("layer").put("affine", JSONArray(listOf(0,0,0,0,0,0)))
        rejects { ArtAnimationPoses.prepare(layer, matrix) }
        val over = parameters(); over.put("poses", JSONArray((0..32).map { JSONObject().put("frame", it) }))
        rejects { ArtAnimationPoses.prepare(layer, over) }
        assertEquals(before, layer.toString())
        layer.getJSONArray("shapes").getJSONObject(0).put("locked", true)
        val locked = parameters(); locked.getJSONArray("poses").getJSONObject(0).put("shapes", JSONArray().put(JSONObject().put("id", "umbrella").put("opacity", 0)))
        rejects { ArtAnimationPoses.prepare(layer, locked) }
    }
}
