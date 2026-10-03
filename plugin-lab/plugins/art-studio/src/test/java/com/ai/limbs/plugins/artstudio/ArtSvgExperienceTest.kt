package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cloud JVM regression cases; no Android drawing methods are executed here. */
class ArtSvgExperienceTest {
    private fun rejected(block: () -> Unit): String {
        try { block(); fail("Expected rejected input") }
        catch (error: IllegalArgumentException) { return requireNotNull(error.message) }
        error("Unreachable")
    }
    @Test fun numericAndPercentageStopsHaveTheSameMeaning() {
        assertEquals(0.0, ArtSceneSvgGeometry.stopOffset("0%"), 0.0)
        assertEquals(0.35, ArtSceneSvgGeometry.stopOffset("35%"), 0.0)
        assertEquals(1.0, ArtSceneSvgGeometry.stopOffset(" 100% "), 0.0)
        assertEquals(ArtSceneSvgGeometry.stopOffset(".35"), ArtSceneSvgGeometry.stopOffset("35%"), 0.0)
    }
    @Test fun stopUnitsDoNotEnablePercentageCoordinatesOrOutOfRangeStops() {
        for (text in listOf("-1%", "101%", "NaN", "Infinity", "10px", "%"))
            assertTrue(rejected { ArtSceneSvgGeometry.stopOffset(text) }.isNotBlank())
        assertTrue(rejected { ArtSceneSvgGeometry.num("50%") }.contains("无单位"))
    }
    private fun parts(count: Int) = List(count) {
        ArtPathTopology.Part(mutableListOf(
            ArtPathGeometry.Node(ArtPathGeometry.Vec(0.0, it.toDouble())),
            ArtPathGeometry.Node(ArtPathGeometry.Vec(1.0, it.toDouble()))), false)
    }
    @Test fun sixtyFourSubpathsAreRetainedAndSixtyFiveExplainHowToSplit() {
        val accepted = ArtPathTopology.geometry(parts(64))
        assertEquals(128, accepted.getJSONArray("points").length())
        val message = rejected { ArtPathTopology.geometry(parts(65)) }
        assertTrue(message.contains("64"))
        assertTrue(message.contains("65"))
        assertTrue(message.contains("多个 path"))
    }
    @Test fun attributeErrorsIdentifyTheirElementAndAttribute() {
        val root = ArtSceneSvg.parse("<svg xmlns='http://www.w3.org/2000/svg'><circle id='moon' r='40px'/></svg>")
        val circle = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "circle").item(0) as org.w3c.dom.Element
        val message = rejected { ArtSceneSvgGeometry.atElement(circle) { ArtSceneSvgGeometry.number(circle, "r") } }
        assertTrue(message.contains("circle id=moon"))
        assertTrue(message.contains("属性 r"))
        assertTrue(message.contains("40px"))
    }
    @Test fun incompletePathCommandNamesTheMissingArguments() {
        assertTrue(rejected { ArtSvgPath.commands("M0 0 C1 2") }.contains("C 需要6"))
        assertTrue(rejected { ArtSvgPath.commands("M0 0 L Z 2") }.contains("遇到 'Z'"))
    }
    @Test fun compactReceiptRetainsCommitAndPreviewEvidenceWithoutGeometryOrHistory() {
        val preview = JSONObject().put("status", "error").put("operationApplied", true).put("error", "render failed")
        val result = JSONObject().put("id", "document").put("revision", 7).put("svgApplied", true)
            .put("state", JSONObject().put("layers", JSONArray())).put("operations", JSONArray()).put("timeline", JSONArray())
            .put("canUndo", true).put("svgTouchedLayerIds", JSONArray().put("layer")).put("thumbnail", preview)
            .put("mcp_content", JSONArray().put(JSONObject().put("type", "image")))
        assertSame(result, ArtSvgReceipt.format(result, ArtSvgReceipt.mode(JSONObject())))
        val receipt = ArtSvgReceipt.format(result, "receipt")
        for (key in listOf("state", "operations", "timeline")) {
            assertFalse(receipt.has(key))
            assertTrue(result.has(key))
        }
        assertEquals(7, receipt.getInt("revision"))
        assertEquals("document", receipt.getString("documentId"))
        assertTrue(receipt.getBoolean("historyWritten"))
        assertSame(preview, receipt.getJSONObject("thumbnail"))
        assertTrue(receipt.has("mcp_content"))
        assertTrue(receipt.getBoolean("canUndo"))
        assertFalse(ArtSvgReceipt.format(JSONObject().put("id", "document").put("svgApplied", false), "receipt").getBoolean("historyWritten"))
        rejected { ArtSvgReceipt.mode(JSONObject().put("responseMode", "other")) }
    }
}
