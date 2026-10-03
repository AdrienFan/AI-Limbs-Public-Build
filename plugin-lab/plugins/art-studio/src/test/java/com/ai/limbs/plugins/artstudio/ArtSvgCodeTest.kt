package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

/** Pure indexing/XML boundary checks for cloud JVM tests; no Android rendering is invoked. */
class ArtSvgCodeTest {
    private fun invalid(block:()->Unit){try {block();fail("Expected rejected input")}catch(e:IllegalArgumentException){}catch(e:IllegalStateException){}}
    private fun svg(content:String)="<svg xmlns=\"http://www.w3.org/2000/svg\">$content</svg>"
    @Test fun quotedGreaterThanDoesNotEndTag() {
        val s="<svg><g id='layer_a' data-name='A > B'><path id='shape_a'/></g></svg>"
        val ranges=ArtSvgCodeIndex.ranges(s);assertEquals("<path id='shape_a'/>",s.substring(ranges[1].start,ranges[1].end))
        assertTrue(s.substring(ranges[0].start,ranges[0].end).endsWith("</g>"))
    }
    @Test fun innermostObjectAndAncestorAreIndexed() {
        val s="<svg><g id='layer_a'><g id='layer_b'><path id='shape_a'/></g></g></svg>"
        val r=ArtSvgCodeIndex.ranges(s);assertEquals("shape_a",ArtSvgCodeIndex.at(r,s.indexOf("shape_a"))?.id)
        assertEquals("layer_b",ArtSvgCodeIndex.at(r,s.indexOf("</g>"))?.id)
    }
    @Test fun offsetsUseUtf16IncludingSupplementaryCharacters() {
        val s="<svg><!--月亮🌙--><path id='shape_a'/></svg>";val r=ArtSvgCodeIndex.ranges(s).single()
        assertEquals(s.indexOf("<path"),r.start);assertEquals("<path id='shape_a'/>",s.substring(r.start,r.end))
    }
    @Test fun commentsAndCdataDoNotCreateFalseObjects() {
        val s="<svg><!--<g id='fake'/>--><metadata id='text_a'><![CDATA[<g id='other'/>]]></metadata></svg>"
        assertEquals(listOf("text_a"),ArtSvgCodeIndex.ranges(s).map {it.id})
    }
    @Test fun multilineBlockIncludesItsClosingTag() {
        val s="<svg>\n<g id=\"layer_a\">\n<path id=\"shape_a\"/>\n</g>\n</svg>";val r=ArtSvgCodeIndex.ranges(s).first()
        assertEquals("<g id=\"layer_a\">\n<path id=\"shape_a\"/>\n</g>",s.substring(r.start,r.end))
    }
    @Test fun duplicateIdsAreRejected(){invalid {ArtSvgCodeIndex.ranges("<svg><g id='a'/><g id='a'/></svg>")}}
    @Test fun mismatchedClosingTagIsRejected(){invalid {ArtSvgCodeIndex.ranges("<svg><g id='a'></path></svg>")}}
    @Test fun incompleteDraftIsNotAnApplicableIndex(){invalid {ArtSvgCodeIndex.ranges("<svg><g id='a'")}}
    @Test fun svgAlphaColorRoundTripsNativeArgb() {
        assertEquals("#80402010",ArtSceneSvgGeometry.color("#40201080"));assertEquals("#40201080",ArtSceneSvgGeometry.css("#80402010"))
    }
    @Test fun shorthandAndNoneAreExplicit() {assertEquals("#ffaabbcc",ArtSceneSvgGeometry.color("#abc"));assertEquals("#00000000",ArtSceneSvgGeometry.color("none"))}
    @Test fun numericListKeepsSignsAndExponents() {assertEquals(listOf(1.0,-2.0,30.0,.5),ArtSceneSvgGeometry.numbers("1,-2 3e1 .5"))}
    @Test fun malformedNumbersAndUnsupportedColorsReject() {invalid {ArtSceneSvgGeometry.numbers("1px 2")};invalid {ArtSceneSvgGeometry.color("url(http://example.com)")}}
    @Test fun supportedXmlHasLiteralStableIds(){assertEquals("svg",ArtSceneSvg.parse(svg("<g id='layer_a'><path id='shape_a' d='M0 0 L1 1'/></g>")).localName)}
    @Test fun scriptsAndUnknownAttributesReject() {invalid {ArtSceneSvg.parse(svg("<script/>"))};invalid {ArtSceneSvg.parse(svg("<path d='M0 0 L1 1' onclick='run()'/>"))}}
    @Test fun dtdAndEntityDeclarationsReject(){invalid {ArtSceneSvg.parse("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]>"+svg("&x;"))}}
    @Test fun processingInstructionsOutsideRootReject(){invalid {ArtSceneSvg.parse("<?run value='x'?>"+svg(""))}}
    @Test fun graphicCannotHideNestedObjects(){invalid {ArtSceneSvg.parse(svg("<path id='a'><path id='b'/></path>"))}}
    @Test fun entityEncodedIdsRejectForAccurateOffsets(){invalid {ArtSceneSvg.parse(svg("<path id='shape_&#97;'/>"))}}
}
