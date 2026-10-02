package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

/** Pure ZIP/source contracts, written for later cloud execution; bitmap/phone cases need deployment. */
class ArtReferenceFilesTest {
    private val pngHeader=byteArrayOf(-119,80,78,71,13,10,26,10)
    private fun ref(index:Int)=JSONObject().put("id","REF$index").put("asset","ASSET$index")
        .put("width",20).put("height",10).put("matrix",JSONArray(listOf(1,0,0,1,index*30,5)))
        .put("opacity",0.5).put("saturation",0.3).put("visible",false).put("locked",true).put("keepAspect",true).put("name","参考$index")
        .put("externalSource","https://example.invalid/ref-$index.png").put("storage","linked_snapshot")
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray {
        val output=ByteArrayOutputStream();ZipOutputStream(output).use {z->entries.forEach {(name,data)->
            z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}}
        return output.toByteArray()
    }
    private fun manifest(vararg names:String)=JSONObject().put("format",ArtReferenceFiles.FORMAT)
        .put("references",JSONArray(names.map {JSONObject().put("image",it)})).toString().toByteArray()
    @Test fun portableRoundTripPreservesOrderMatrixAndStyleAndDropsExternalSources() {
        val data=ArtReferenceFiles.encode(listOf(ref(0),ref(1)),false) {pngHeader}
        val result=ArtReferenceFiles.decode(data);assertEquals(2,result.references.size)
        for(i in 0..1) {
            val r=result.references[i];assertEquals("参考$i",r.getString("name"));assertEquals(i*30,r.getJSONArray("matrix").getInt(4))
            assertEquals(0.5,r.getDouble("opacity"),0.0);assertEquals(0.3,r.getDouble("saturation"),0.0)
            assertFalse(r.getBoolean("visible"));assertTrue(r.getBoolean("locked"));assertTrue(r.getBoolean("keepAspect"))
            assertFalse(r.has("externalSource"));assertFalse(r.has("id"));assertFalse(r.has("asset"))
            assertArrayEquals(pngHeader,result.images.getValue("images/$i.png"))
        }
    }
    @Test fun retainedLinksStillCarrySnapshotsAndDoNotMutateOriginals() {
        val original=ref(0);val result=ArtReferenceFiles.decode(ArtReferenceFiles.encode(listOf(original),true) {pngHeader})
        assertEquals(original.getString("externalSource"),result.references[0].getString("externalSource"))
        assertArrayEquals(pngHeader,result.images.getValue("images/0.png"));assertTrue(original.has("asset"));assertTrue(original.has("id"))
    }
    @Test fun compressedEntriesCanArriveBeforeTheManifest() {
        val result=ArtReferenceFiles.decode(zip("images/0.png" to pngHeader,"manifest.json" to manifest("images/0.png")))
        assertEquals(1,result.references.size)
    }
    @Test(expected=IllegalArgumentException::class) fun pathTraversalIsRejectedBeforeReadingEntryData() {
        ArtReferenceFiles.decode(zip("../outside.png" to pngHeader,"manifest.json" to manifest("../outside.png")))
    }
    @Test(expected=IllegalArgumentException::class) fun manifestCannotAliasTwoReferencesToOneImageEntry() {
        ArtReferenceFiles.decode(zip("manifest.json" to manifest("images/0.png","images/0.png"),"images/0.png" to pngHeader))
    }
    @Test(expected=IllegalArgumentException::class) fun undeclaredImagesAreRejectedAsAWholeCollection() {
        ArtReferenceFiles.decode(zip("manifest.json" to manifest("images/0.png"),"images/0.png" to pngHeader,"images/1.png" to pngHeader))
    }
    @Test(expected=IllegalArgumentException::class) fun missingImagesAreRejectedAsAWholeCollection() {
        ArtReferenceFiles.decode(zip("manifest.json" to manifest("images/0.png")))
    }
    @Test(expected=IllegalArgumentException::class) fun nonPngPayloadIsRejected() {
        ArtReferenceFiles.decode(zip("manifest.json" to manifest("images/0.png"),"images/0.png" to ByteArray(8)))
    }
    @Test fun byteLimitAllowsExactlyTheLimitAndRejectsTheNextByte() {
        assertEquals(4,ArtReferenceFiles.readLimited(ByteArrayInputStream(ByteArray(4)),4).size)
        try {ArtReferenceFiles.readLimited(ByteArrayInputStream(ByteArray(5)),4);fail("over-limit input accepted")}
        catch(expected:IllegalArgumentException) {assertTrue(expected.message!!.contains("超过"))}
    }
    @Test fun acceptedSourceSchemesAreExplicitAndPathsAreCanonicalFileUris() {
        assertEquals("file",ArtReferenceFiles.location("/storage/emulated/0/Download/参考.png").scheme)
        assertEquals("https",ArtReferenceFiles.location("https://example.invalid/image.png").scheme)
        assertEquals("content",ArtReferenceFiles.location("content://images/provider/1").scheme)
    }
    @Test(expected=IllegalArgumentException::class) fun accountCredentialsAreNotAcceptedInSavedLinks() {
        ArtReferenceFiles.location("https://user:password@example.invalid/image.png")
    }
    @Test(expected=IllegalArgumentException::class) fun insecureOrUnknownSourceSchemesAreRejected() {
        ArtReferenceFiles.location("http://example.invalid/image.png")
    }
    @Test fun entranceDescriptionsDistinguishSnapshotsPayloadClipboardAndNativeExtent() {
        assertTrue(ArtCapabilityHelp.description("reference.paste").contains("后台不读取系统剪贴板"))
        assertTrue(ArtCapabilityHelp.description("reference.link").contains("reference.refresh"))
        assertTrue(ArtCapabilityHelp.description("reference.capture").contains("默认画布原尺寸"))
        assertTrue(ArtCapabilityHelp.description("reference.collection_import").contains("一次撤销整组"))
        assertEquals("visible",ArtCapabilityHelp.example("reference.capture").getString("source"))
        assertFalse(ArtCapabilityHelp.example("reference.collection_export").getBoolean("keepLinks"))
    }
}
