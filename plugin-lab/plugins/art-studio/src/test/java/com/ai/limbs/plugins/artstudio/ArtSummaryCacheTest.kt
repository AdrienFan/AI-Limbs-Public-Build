package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure projection coverage for cloud CI. No local test execution. */
class ArtSummaryCacheTest {
    private fun document(value:Int=1)=JSONObject().put("id","one")
        .put("base",JSONObject().put("value",value)).put("operations",JSONArray())

    @Test fun unchangedContentSkipsProjectionAndReturnsIndependentMetadata() {
        val cache=ArtSummaryCache();val doc=document();var builds=0
        fun read()=cache.read("one",doc.toString()) {source->
            builds++;JSONObject().put("value",source.getJSONObject("base").getInt("value"))
        }
        read().value.put("value",99)
        assertEquals(1,read().value.getInt("value"));assertEquals(1,builds)
        doc.getJSONObject("base").put("value",2)
        assertEquals(2,read().value.getInt("value"));assertEquals(2,builds)
    }

    @Test fun whitespaceDoesNotChangeSavedDigestAndFailureCannotPublishProjection() {
        val cache=ArtSummaryCache();val source=document().toString()
        val first=cache.read("one",source) {_->JSONObject().put("value",1)}
        val formatted=cache.read("one",document().toString(2)) {_->JSONObject().put("value",1)}
        assertEquals(first.documentDigest,formatted.documentDigest)
        try {
            cache.read("one",document(2).toString()) {_->error("invalid state")}
            fail("Must propagate projection error")
        } catch(expected:IllegalStateException) {assertEquals("invalid state",expected.message)}
        assertEquals(1,cache.read("one",document().toString(2)) {_->error("must reuse successful entry")}.value.getInt("value"))
        try {cache.read("another",source) {_->JSONObject()};fail("Must reject wrong document")}
        catch(expected:IllegalArgumentException) {assertEquals("草稿工程编号不匹配",expected.message)}
    }

    @Test fun savedDigestIncludesLegacyNormalizationDuringProjection() {
        val cache=ArtSummaryCache();val source=document().toString()
        val result=cache.read("one",source) {doc->
            doc.getJSONObject("base").put("legacyDefault",45)
            JSONObject().put("value",1)
        }
        val normalized=document().apply {getJSONObject("base").put("legacyDefault",45)}
        assertEquals(ArtDocumentDigest.of(normalized.toString()),result.documentDigest)
        assertEquals(result.documentDigest,cache.read("one",source) {_->error("Must reuse normalized projection")}.documentDigest)
    }

    @Test fun digestKeepsPublishedSha256Encoding() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ArtDocumentDigest.of("abc"))
    }
}
