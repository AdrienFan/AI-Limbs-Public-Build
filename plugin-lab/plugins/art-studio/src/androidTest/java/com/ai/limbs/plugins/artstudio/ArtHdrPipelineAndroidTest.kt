package com.ai.limbs.plugins.artstudio

import android.graphics.*
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android/Skia and packaged JNI: no mock Bitmap can prove HDR preservation. */
@RunWith(AndroidJUnit4::class)
class ArtHdrPipelineAndroidTest {
    private lateinit var directory:File
    private lateinit var store:ArtStore
    @Before fun prepare() {
        directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"hdr-"+UUID.randomUUID())
        store=ArtStore(directory)
    }
    @After fun clean() {directory.deleteRecursively()}
    private fun request(s:JSONObject)=JSONObject().put("documentId",s.getString("id")).put("expectedRevision",s.getInt("revision"))
    private fun render(s:JSONObject)=ArtRenderer.render(store,s)
    private fun pixel(s:JSONObject,x:Int,y:Int):FloatArray {val b=render(s);return try {ArtFloatPixels.read(b,Rect(x,y,x+1,y+1))} finally {b.recycle()}}
    private fun assertRgba(expected:FloatArray,actual:FloatArray) {assertArrayEquals(expected,actual,.02f)}
    private fun openHdr(w:Int=32,h:Int=32):JSONObject {
        val values=FloatArray(w*h*4) {i->floatArrayOf(4f,2f,.5f,1f)[i%4]}
        return store.openImage(Base64.encodeToString(ArtOpenExr.encode(w,h,values,1024*1024),Base64.NO_WRAP),"HDR test")
    }
    @Test fun nativeReadWriteAndExrAssetsKeepSignedHDRAndHalfAlpha() {
        val expected=floatArrayOf(4f,-.25f,2f,.5f,8f,3f,1f,1f)
        val image=ArtFloatPixels.from(2,1,expected)
        try {
            assertEquals(Bitmap.Config.RGBA_F16,image.config)
            assertRgba(expected,ArtFloatPixels.read(image))
            val bytes=ArtImagePolicy.encodeAsset(image,1024*1024);assertTrue(ArtOpenExr.matches(bytes))
            val decoded=ArtImagePolicy.decodeAssetBytes(bytes)
            try {assertRgba(expected,ArtFloatPixels.read(decoded))} finally {decoded.recycle()}
        } finally {image.recycle()}
    }
    @Test fun sixteenBitPngDecoderRetainsDifferencesBelowEightBitQuantization() {
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("hdr/reference-rgb16.png").use {it.readBytes()}
        val result=ArtImagePolicy.decode(bytes);val image=result.first
        try {
            assertEquals(Bitmap.Config.RGBA_F16,image.config)
            val pixels=ArtFloatPixels.read(image)
            assertTrue("16-bit differences were quantized away",pixels[0]<pixels[4])
            assertEquals(ArtFloatPixels.linear(32768f/65535),pixels[0],.0003f)
        } finally {image.recycle()}
    }
    @Test fun importedHDRSurvivesRendererFillUndoSaveReopenAndEXRExport() {
        var state=openHdr();val layer=state.getJSONObject("state").getString("selectedLayerId")
        assertRgba(floatArrayOf(4f,2f,.5f,1f),pixel(state,5,5))
        state=store.encloseFill("AWEI",request(state).put("layerId",layer).put("shape","rect")
            .put("points",JSONArray("[[4,4],[12,12]]")).put("mode","all").put("includeContour",true).put("antialias",0)
            .put("linearRGBA",JSONArray("[8,0.5,2,1]")))
        assertRgba(floatArrayOf(8f,.5f,2f,1f),pixel(state,6,6))
        state=store.history("AWEI",false);assertRgba(floatArrayOf(4f,2f,.5f,1f),pixel(state,6,6))
        state=store.history("AWEI",true);assertRgba(floatArrayOf(8f,.5f,2f,1f),pixel(state,6,6))
        val archive=store.save();state=store.importArchive(File(archive.getString("path")).readBytes())
        assertRgba(floatArrayOf(8f,.5f,2f,1f),pixel(state,6,6))
        val exported=store.exportImage("exr","hdr",request(state))
        val image=ArtOpenExr.decode(File(exported.getString("path")).readBytes(),1024)
        assertEquals(8f,image.pixels[(6*32+6)*4],.02f)
        assertTrue(exported.getBoolean("hdrPreserved"))
    }
    @Test fun floatedGroupTransformWritesDocumentOverlayIntoOwningLocalSpace() {
        val snapshot=openHdr();val state=snapshot.getJSONObject("state");val layer=state.getJSONArray("layers").getJSONObject(0)
        val group=JSONObject(layer.toString()).put("id",UUID.randomUUID().toString()).put("kind","group").put("asset","")
            .put("x",3).put("y",2).put("scale",.75).put("rotation",10).put("parentId","")
        layer.put("parentId",group.getString("id"));state.getJSONArray("layers").put(group)
        val overlay=ArtFloatPixels.from(2,2,FloatArray(16) {floatArrayOf(8f,1f,2f,1f)[it%4]})
        val id=UUID.randomUUID().toString()
        try {store.assetFile(id).writeBytes(ArtImagePolicy.encodeAsset(overlay,100000))} finally {overlay.recycle()}
        layer.put("contentOrder",JSONArray().put(ArtPixelCoordinates.stamp(JSONObject().put("kind","paste").put("asset",id).put("x",8).put("y",8),state,layer)))
        assertRgba(floatArrayOf(8f,1f,2f,1f),pixel(snapshot,8,8))
    }
    @Test fun colorizeHDRPaletteAndAnimationCelsPreserveSeparateFloatOutputs() {
        var snap=store.create(20,20,"#00000000")
        val source=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.colorizeCreate("AWEI",request(snap).put("sourceLayerId",source))
        val mask=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.colorizeStroke("AWEI",request(snap).put("maskId",mask).put("points",JSONArray("[[5,5]]")).put("width",3)
            .put("color","#FFFFFFFF").put("linearRGBA",JSONArray("[4,2,1,1]")))
        snap=store.colorizeUpdate("AWEI",request(snap).put("maskId",mask))
        assertRgba(floatArrayOf(4f,2f,1f,1f),pixel(snap,15,15))
        snap=store.apply("AWEI","ANIMATION_KEY",request(snap).put("layerId",mask).put("frame",5).put("action","blank"))
        snap=store.apply("AWEI","ANIMATION_TIME",request(snap).put("frame",5))
        assertEquals(0f,pixel(snap,15,15)[3],.001f)
        snap=store.apply("AWEI","ANIMATION_TIME",request(snap).put("frame",0))
        assertRgba(floatArrayOf(4f,2f,1f,1f),pixel(snap,15,15))
    }
    @Test fun patchKeepsHDRFloatDonorsAndUndoRestoresOriginal() {
        var snap=openHdr();val layer=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.smartPatch("AWEI",request(snap).put("layerId",layer).put("points",JSONArray("[[15,15]]")).put("width",3)
            .put("patchRadius",1).put("searchRadius",16).put("accuracy",10).put("levels",1).put("feather",0))
        assertRgba(floatArrayOf(4f,2f,.5f,1f),pixel(snap,15,15))
        snap=store.history("AWEI",false);assertRgba(floatArrayOf(4f,2f,.5f,1f),pixel(snap,15,15))
    }
    @Test fun rotatedBezierAndIndependentMaskRetainEditableNodes() {
        var snap=store.create(32,32,"#00000000")
        snap=store.bezierSelectionCreate("AWEI",request(snap).put("nodes",JSONArray("[{\"x\":4,\"y\":4},{\"x\":16,\"y\":4},{\"x\":16,\"y\":16},{\"x\":4,\"y\":16}]")))
        snap=store.rotateSelection("AWEI",request(snap).put("degrees",30))
        val nodes=ArtBezierSelection.nodes(ArtBezierSelection.component(snap.getJSONObject("state").getJSONObject("selection"),0))
        assertEquals(4,nodes.size)
        snap=store.selectionMask("AWEI",request(snap).put("action","save").put("name","rotated"))
        val mask=ArtMenuOperations.layers(snap.getJSONObject("state")).first {it.getString("kind")=="selection_mask"}.getString("id")
        snap=store.apply("AWEI","SELECTION_CLEAR",request(snap))
        snap=store.selectionMask("AWEI",request(snap).put("action","activate").put("id",mask))
        val restored=ArtBezierSelection.nodes(ArtBezierSelection.component(snap.getJSONObject("state").getJSONObject("selection"),0))
        for(i in nodes.indices) {assertEquals(nodes[i].point.x,restored[i].point.x,.001);assertEquals(nodes[i].point.y,restored[i].point.y,.001)}
    }
    @Test fun magneticReeditReplacesOriginalContributionAndPreservesEarlierSelection() {
        var snap=store.create(20,20,"#00000000");val layer=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.basicSelection("AWEI",request(snap).put("shape","rect").put("x",1).put("y",1).put("width",2).put("height",2).put("antialias",0))
        fun commit(points:String,reedit:Boolean) {
            snap=store.magneticCommit("AWEI",request(snap).put("layerId",layer).put("mode","add").put("antialias",0)
                .put("points",JSONArray(points)).put("anchors",JSONArray(points)).put("reedit",reedit))
        }
        commit("[[4,4],[8,4],[8,8],[4,8]]",false)
        commit("[[10,10],[14,10],[14,14],[10,14]]",true)
        val selection=snap.getJSONObject("state").getJSONObject("selection")
        val coverage=ArtSoftSelection.Sampler(selection)
        assertEquals(255,coverage.at(2.0,2.0));assertEquals(0,coverage.at(6.0,6.0));assertEquals(255,coverage.at(12.0,12.0))
        assertTrue(selection.has("magneticSource"))
    }
    @Test fun multiscaleColorizeKeepsNativeResolutionAndHDRAcrossTileSeams() {
        var snap=store.create(1025,1025,"#00000000");val source=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.colorizeCreate("AWEI",request(snap).put("sourceLayerId",source));val mask=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.colorizeStroke("AWEI",request(snap).put("maskId",mask).put("points",JSONArray("[[10,10]]")).put("width",3)
            .put("color","#FFFFFFFF").put("linearRGBA",JSONArray("[4,2,1,1]")))
        snap=store.colorizeUpdate("AWEI",request(snap).put("maskId",mask))
        assertTrue(snap.getJSONObject("colorizeResult").getInt("coarseScale")>1)
        val image=render(snap)
        try {
            assertEquals(1025,image.width);assertEquals(1025,image.height)
            for(x in listOf(10,511,512,1024))assertRgba(floatArrayOf(4f,2f,1f,1f),ArtFloatPixels.read(image,Rect(x,x,x+1,x+1)))
        } finally {image.recycle()}
    }
    @Test fun reopeningMagneticAnchorsUsesOriginalBoundsRatherThanTheSmallerResult() {
        var snap=store.create(20,20,"#00000000");val layer=snap.getJSONObject("state").getString("selectedLayerId")
        snap=store.basicSelection("AWEI",request(snap).put("shape","rect").put("x",0).put("y",0).put("width",20).put("height",20).put("antialias",0))
        snap=store.magneticCommit("AWEI",request(snap).put("layerId",layer).put("mode","replace").put("antialias",0).put("limitToSelection",true)
            .put("bounds",JSONObject().put("x",0).put("y",0).put("width",20).put("height",20))
            .put("anchors",JSONArray("[[4,4],[8,4],[8,8],[4,8]]")).put("points",JSONArray("[[4,4],[8,4],[8,8],[4,8]]")))
        val options=ArtMagneticSelection.options(snap.getJSONObject("state").getJSONObject("selection").getJSONObject("magneticSource"))
        val p=request(snap).put("layerId",layer);options.keys().forEach {p.put(it,options.get(it))}
        val image=store.magneticReference(p)
        assertEquals(Rect(0,0,20,20),image.rect)
    }
}
