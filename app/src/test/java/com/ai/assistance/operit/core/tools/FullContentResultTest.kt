package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolResult
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

class FullContentResultTest {
    private val fullText = "长段落".repeat(100) + " 🥰 \"<&>\n第二行\t"

    private fun page(format: String = "xml", detail: String = "summary") = UIPageResultData(
        "example.app", "MainActivity",
        SimplifiedUINode("TextView", fullText, "完整描述", "body", "[0,0][10,10]", false, emptyList()),
        format, detail
    )

    @Test
    fun jsonRetainsFullTextBeyondDisplayPreview() {
        val source = page("json", "full")
        assertEquals(fullText, Json { ignoreUnknownKeys = true }.decodeFromString<UIPageResultData>(source.toString()).uiElements.text)
    }

    @Test
    fun fullXmlRoundTripsLongTextAndSpecialCharacters() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(page(detail = "full").toString())))
        assertEquals(fullText, document.getElementsByTagName("node").item(0).attributes.getNamedItem("text").nodeValue)
    }

    @Test
    fun displayAndStructuredPayloadRemainSeparateAcrossSerialization() {
        val actual = page()
        val result = ToolResult("get_page_info", true, StringResultData("短预览"), structuredResult = actual)
        val decoded = Json.decodeFromString<ToolResult>(Json.encodeToString(result))
        assertEquals("短预览", decoded.result.toString())
        assertEquals(fullText, (decoded.structuredResult as UIPageResultData).uiElements.text)
        assertTrue(actual.toString().contains("..."))
    }
}
