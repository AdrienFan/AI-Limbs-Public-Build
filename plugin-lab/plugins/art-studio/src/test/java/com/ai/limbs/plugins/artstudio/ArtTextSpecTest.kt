package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtTextSpecTest {
    private fun base(content:String="晨光与海")=JSONObject().put("content",content).put("fontId","explicit-test-font")
    private fun svg(source:String)=ArtSvgText.parse(source,ArtTextSpec.style(base())) {family,weight,italic,current->
        require(family in setOf("","test-family"));require(weight==null||weight==700);require(italic==null||italic)
        current
    }
    private fun rejected(block:()->Unit) {
        try {block();fail("Expected explicit rejection")}catch(expected:IllegalArgumentException) {assertTrue(expected.message.orEmpty().isNotBlank())}
        catch(expected:IllegalStateException) {assertTrue(expected.message.orEmpty().isNotBlank())}
    }
    @Test fun richRangeUsesUtf16AndPreservesCombiningSequences() {
        val text=base("A\u0301海").put("sourceMode","rich").put("spans",JSONArray().put(JSONObject().put("start",0).put("end",2).put("color","#FFCC8844")))
        val normalized=ArtTextSpec.normalize(text)
        assertEquals(2,normalized.getJSONArray("spans").getJSONObject(0).getInt("end"))
        text.getJSONArray("spans").getJSONObject(0).put("end",1);rejected {ArtTextSpec.normalize(text)}
    }
    @Test fun overlappingSpansAreRejected() {
        val a=JSONArray().put(JSONObject().put("start",0).put("end",3)).put(JSONObject().put("start",2).put("end",4))
        rejected {ArtTextSpec.normalize(base().put("spans",a))}
    }
    @Test fun scalarValidationRejectsInvalidUnicodeAndNaN() {
        rejected {ArtTextSpec.normalize(base("\uD800"))}
        rejected {ArtTextSpec.normalize(base().put("fontSize",0))}
        rejected {ArtTextSpec.normalize(base().put("fontFeatures","not-a-feature"))}
    }
    @Test fun verticalAndBidiSettingsRemainEditable() {
        val p=ArtTextSpec.normalize(base("مرحبا 2026").put("writingMode","vertical-rl").put("direction","rtl").put("language","ar"))
        assertEquals("rtl",p.getString("direction"));assertEquals("vertical-rl",p.getString("writingMode"))
    }
    @Test fun geometryMutualExclusionAndClosedInsideAreChecked() {
        val g=JSONObject().put("d","M0 0 H300 V200 H0 Z")
        rejected {ArtTextSpec.normalize(base().put("textPath",g).put("shapeInside",g))}
        rejected {ArtTextSpec.normalize(base().put("shapeInside",JSONObject().put("d","M0 0 H300")))}
        assertTrue(ArtTextSpec.normalize(base().put("shapeInside",g)).has("shapeInside"))
    }
    @Test fun richReplacementRetainsUnaffectedIntervals() {
        val a=JSONArray().put(JSONObject().put("start",1).put("end",3).put("underline",true))
        val edited=ArtRichText.edit("abcd","abXYZcd",a)
        assertEquals(1,edited.getJSONObject(0).getInt("start"));assertEquals(6,edited.getJSONObject(0).getInt("end"))
    }
    @Test fun richSelectionOverridesOnlyTheSelectedInterval() {
        val a=JSONArray().put(JSONObject().put("start",0).put("end",4).put("color","#FF000000"))
        val edited=ArtRichText.apply("abcd",a,1,3,JSONObject().put("color","#FFFF0000"))
        assertEquals(3,edited.length());assertEquals("#FFFF0000",edited.getJSONObject(1).getString("color"))
    }
    @Test fun svgNestedTspanKeepsOneBidiBlockAndIndependentStyle() {
        val parsed=svg("<svg xmlns='http://www.w3.org/2000/svg'><text x='20' y='60' direction='rtl'>海<tspan fill='#f00'>光</tspan></text></svg>")
        val chunks=parsed.getJSONArray("svgChunks")
        assertEquals(2,chunks.length());assertEquals(chunks.getJSONObject(0).getInt("block"),chunks.getJSONObject(1).getInt("block"))
        assertEquals("#FFff0000",parsed.getJSONArray("spans").getJSONObject(1).getString("color"))
        assertTrue(chunks.getJSONObject(1).getJSONObject("positions").getJSONArray("x").isNull(0))
    }
    @Test fun svgLocalTextPathRetainsRichChildren() {
        val parsed=svg("<svg><defs><path id='p' d='M0 100 C80 0 240 200 320 100'/></defs><text><textPath href='#p' startOffset='10%'>海<tspan fill='red'>光</tspan></textPath></text></svg>")
        val chunks=parsed.getJSONArray("svgChunks");assertEquals(2,chunks.length())
        assertEquals(10.0,chunks.getJSONObject(1).getJSONObject("textPath").getDouble("startPercent"),0.0)
    }
    @Test fun svgRejectsEntitiesExternalLinksAndUnsupportedCss() {
        rejected {svg("<!DOCTYPE svg [<!ENTITY ext SYSTEM 'file:///tmp/private'>]><svg><text>&ext;</text></svg>")}
        rejected {svg("<svg><text><textPath href='https://example.com/path'>x</textPath></text></svg>")}
        rejected {svg("<text style='filter:blur(1px)'>x</text>")}
    }
    @Test fun svgUsesRgbaColorAndInheritedOpacityOnlyOnce() {
        val parsed=svg("<text fill='#12345680' fill-opacity='0.5'>a<tspan>b</tspan></text>")
        val a=parsed.getJSONArray("spans");assertEquals("#40123456",a.getJSONObject(0).getString("color"));assertEquals(a.getJSONObject(0).getString("color"),a.getJSONObject(1).getString("color"))
    }
    @Test fun svgCharacterListsCountSupplementaryCharactersOnce() {
        val parsed=svg("<text x='1 2 3'>A\uD83D\uDE00海</text>")
        assertEquals(3,parsed.getJSONArray("svgChunks").getJSONObject(0).getJSONObject("positions").getJSONArray("x").length())
    }
    @Test fun svgPathTokenizerSupportsCurvesArcsAndRejectsUnknownTokens() {
        assertEquals(5,ArtSvgPath.commands("M0 0 l10 0 Q20 20 30 0 A5 5 0 0 1 40 0 Z").size)
        rejected {ArtSvgPath.commands("M0 0 R10 10")}
        rejected {ArtSvgPath.commands("M0 0 A5 5 0 2 1 10 0")}
    }
    @Test fun svgScopeDoesNotClaimTheEntireStandard() {
        assertFalse(ArtTextSpec.info().getJSONObject("svg").getBoolean("completeSvgStandard"))
        assertTrue(ArtTextSpec.info().getJSONObject("svg").getJSONArray("unsupported").length()>0)
    }
}
