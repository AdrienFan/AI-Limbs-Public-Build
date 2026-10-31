package com.ai.limbs.plugins.artstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtMenuFilterAndroidTest {
    private lateinit var directory:File
    private lateinit var store:ArtStore
    @Before fun prepare() {
        directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"filters-"+UUID.randomUUID())
        store=ArtStore(directory)
    }
    @After fun clean() {directory.deleteRecursively()}
    private fun request(s:JSONObject)=JSONObject().put("documentId",s.getString("id")).put("expectedRevision",s.getInt("revision"))
    private fun seeded():JSONObject {
        val s=store.create(8,8,"#00000000","filter regression")
        return store.encloseFill("AWEI",request(s).put("layerId",s.getJSONObject("state").getString("selectedLayerId"))
            .put("shape","rect").put("points",JSONArray("[[0,0],[8,8]]")).put("mode","all")
            .put("includeContour",true).put("antialias",0).put("color","#FFFFFFFF").put("linearRGBA",JSONArray("[8,0.5,2,1]")))
    }
    private fun pixels(s:JSONObject):FloatArray {
        val b=ArtRenderer.render(store,s)
        return try {ArtFloatPixels.read(b)} finally {b.recycle()}
    }
    @Test fun previewWritesNoHistoryAssetsOrLastFilterAndCommitUndoesExactly() {
        val s=seeded();val before=pixels(s)
        val assets=File(directory,"assets").list()?.toSet()
        val preview=store.previewMenuBitmap("filter.gaussian_blur",request(s).put("sigmaX",1).put("sigmaY",1))
        preview.recycle()
        assertEquals(s.getInt("revision"),store.menuContext().getJSONObject("document").getInt("revision"))
        assertFalse(store.menuContext().getJSONObject("document").getJSONObject("state").has("lastFilter"))
        assertEquals(assets,File(directory,"assets").list()?.toSet())
        val changed=store.executeMenu("AWEI","filter.gaussian_blur",request(s).put("sigmaX",1).put("sigmaY",1))
        assertEquals(s.getInt("revision")+1,changed.getInt("revision"))
        assertArrayEquals(before,pixels(changed),.01f)
        val undone=store.history("AWEI",redo=false)
        assertArrayEquals(before,pixels(undone),0f)
    }
    @Test fun stalePreviewAndCommitBothReject() {
        val s=seeded();val p=request(s).put("expectedRevision",s.getInt("revision")-1)
        assertThrows(IllegalArgumentException::class.java) {store.previewMenuBitmap("filter.sharpen",p)}
        assertThrows(IllegalArgumentException::class.java) {store.executeMenu("AWEI","filter.sharpen",p)}
        assertEquals(s.getInt("revision"),store.menuContext().getJSONObject("document").getInt("revision"))
    }
    @Test fun selectionControlsOutputButDoesNotRemoveNeighborSamples() {
        val source=floatArrayOf(8f,0f,0f,1f,0f,0f,0f,0f,0f,0f,0f,0f)
        val bitmap=ArtFloatPixels.from(3,1,source)
        try {
            val selection=JSONObject().put("shape","rect").put("x",1).put("y",0).put("width",1).put("height",1)
            ArtMenuOperations.filter(bitmap,"filter.gaussian_blur",JSONObject().put("sigmaX",1).put("sigmaY",0),selection)
            val out=ArtFloatPixels.read(bitmap)
            assertArrayEquals(source.copyOfRange(0,4),out.copyOfRange(0,4),0f)
            assertArrayEquals(source.copyOfRange(8,12),out.copyOfRange(8,12),0f)
            assertTrue(out[7]>0f);assertEquals(8f,out[4]/out[7],.02f)
        } finally {bitmap.recycle()}
    }
    @Test fun menuFilteringPreservesOtherAnimationCels() {
        var s=seeded();val id=s.getJSONObject("state").getString("selectedLayerId")
        s=store.animationKey("AWEI",request(s).put("layerId",id).put("frame",5).put("action","blank"))
        s=store.executeMenu("AWEI","filter.invert",request(s))
        val zero=pixels(s)
        s=store.animationSeek("AWEI",request(s).put("frame",5))
        assertTrue(pixels(s).all {it==0f})
        s=store.animationSeek("AWEI",request(s).put("frame",0))
        assertArrayEquals(zero,pixels(s),0f)
    }
    @Test fun structuredCurvePreviewCommitRepeatAndUndoUseSameConfiguration() {
        val s=seeded();val before=pixels(s)
        val config=JSONObject("{\"red\":[[0,0],[1,2]],\"alpha\":[[0,0],[1,0.5]]}")
        val p=request(s).put("curves",config).put("space","linear")
        val preview=store.previewMenuBitmap("filter.color_adjustment_curves",p);preview.recycle()
        assertEquals(s.getInt("revision"),store.menuContext().getJSONObject("document").getInt("revision"))
        val changed=store.executeMenu("LANER","filter.color_adjustment_curves",p)
        val previous=changed.getJSONObject("state").getJSONObject("lastFilter")
        assertEquals(2.0,previous.getJSONObject("parameters").getJSONObject("curves").getJSONArray("red").getJSONArray(1).getDouble(1),0.0)
        assertEquals(s.getInt("revision")+1,changed.getInt("revision"))
        val once=pixels(changed)
        assertEquals(16f,once[0]/once[3],.02f);assertEquals(.5f,once[3],.001f)
        val repeated=store.executeMenu("LANER","filter_apply_again",request(changed))
        val twice=pixels(repeated)
        assertEquals(32f,twice[0]/twice[3],.04f);assertEquals(.25f,twice[3],.001f)
        assertArrayEquals(once,pixels(store.history("LANER",redo=false)),0f)
        assertArrayEquals(before,pixels(store.history("LANER",redo=false)),0f)
    }
    @Test fun colorFilterHonorsSelectionAndInvalidNestedConfigCannotWrite() {
        val source=floatArrayOf(.5f,.5f,.5f,1f, .5f,.5f,.5f,1f)
        val bitmap=ArtFloatPixels.from(2,1,source)
        try {
            val selection=JSONObject().put("shape","rect").put("x",1).put("y",0).put("width",1).put("height",1)
            ArtMenuOperations.filter(bitmap,"filter.fast_color_overlay",JSONObject().put("color","#FFFF0000").put("space","linear"),selection)
            val out=ArtFloatPixels.read(bitmap)
            assertArrayEquals(source.copyOfRange(0,4),out.copyOfRange(0,4),0f)
            assertArrayEquals(floatArrayOf(1f,0f,0f,1f),out.copyOfRange(4,8),.001f)
        } finally {bitmap.recycle()}
        val s=seeded();val before=File(directory,"assets").list()?.toSet()
        assertThrows(IllegalArgumentException::class.java) {
            store.executeMenu("LANER","filter.levels",request(s).put("levels",JSONObject("{\"master\":{\"typo\":0.1}}")))
        }
        assertEquals(s.getInt("revision"),store.menuContext().getJSONObject("document").getInt("revision"))
        assertEquals(before,File(directory,"assets").list()?.toSet())
    }
    @Test fun filterDiscoveryIncludesSharedCallsAndReportsUnavailableEntries() {
        val s=seeded()
        val catalog=ArtStudioMenuCatalog.filters(store.menuContext(),"filter.cross_channel_adjustment_curves")
        assertFalse(catalog.getBoolean("historyWritten"))
        val entry=catalog.getJSONArray("filters").getJSONObject(0)
        assertTrue(entry.getBoolean("enabled"))
        val calls=entry.getJSONObject("calls")
        assertEquals("plugin.art.studio.menu.preview",calls.getJSONObject("preview").getString("capabilityId"))
        assertEquals("plugin.art.studio.menu.execute",calls.getJSONObject("apply").getString("capabilityId"))
        val args=calls.getJSONObject("apply").getJSONObject("parameters")
        assertEquals(s.getString("id"),args.getString("documentId"))
        assertEquals(s.getInt("revision"),args.getInt("expectedRevision"))
        val rules=args.getJSONObject("parameters").getJSONArray("rules")
        assertEquals(.5,rules.getJSONObject(0).getJSONArray("points").getJSONArray(0).getDouble(1),0.0)
        val transfer=ArtStudioMenuCatalog.filters(store.menuContext(),"filter.color_transfer").getJSONArray("filters").getJSONObject(0)
        assertTrue(transfer.getBoolean("implemented"));assertTrue(transfer.has("calls"))
        assertThrows(IllegalArgumentException::class.java) {ArtStudioMenuCatalog.filters(store.menuContext(),"filter.unknown")}
    }
    @Test fun seededSpatialFilterIsSharedByPreviewAiAndPhoneWithSingleUndoableEdit() {
        val s=seeded();val before=pixels(s);val assets=File(directory,"assets").list()?.toSet()
        val config=JSONObject().put("seed",57).put("level",100).put("opacity",30)
        val p=request(s).apply {config.keys().forEach {put(it,config.get(it))}}
        val preview=store.previewMenuBitmap("filter.random_noise",p);preview.recycle()
        assertEquals(s.getInt("revision"),store.menuContext().getJSONObject("document").getInt("revision"))
        assertEquals(assets,File(directory,"assets").list()?.toSet())
        val ai=store.executeMenu("LANER","filter.random_noise",p)
        assertEquals(s.getInt("revision")+1,ai.getInt("revision"))
        assertEquals(57,ai.getJSONObject("state").getJSONObject("lastFilter").getJSONObject("parameters").getInt("seed"))
        val expected=ArtSpatialFilters.process("filter.random_noise",before,8,8,config)
        val aiPixels=pixels(ai);assertArrayEquals(expected,aiPixels,.01f)
        val undone=store.history("LANER",redo=false);assertArrayEquals(before,pixels(undone),0f)
        val phone=store.executeMenu("AWEI","filter.random_noise",request(undone).apply {config.keys().forEach {put(it,config.get(it))}})
        assertArrayEquals(aiPixels,pixels(phone),0f)
    }
    @Test fun surfaceFilterUsesPremultipliedSelectionMixing() {
        val source=floatArrayOf(1f,1f,1f,1f, 1f,1f,1f,1f)
        val bitmap=ArtFloatPixels.from(2,1,source)
        try {
            val selection=JSONObject().put("shape","rect").put("x",1).put("y",0).put("width",1).put("height",1)
            ArtMenuOperations.filter(bitmap,"filter.height_to_normal_map",JSONObject(),selection)
            val out=ArtFloatPixels.read(bitmap)
            assertArrayEquals(source.copyOfRange(0,4),out.copyOfRange(0,4),0f)
            assertArrayEquals(floatArrayOf(.5f,.5f,1f,1f),out.copyOfRange(4,8),.001f)
        } finally {bitmap.recycle()}
    }
    @Test fun spatialDiscoveryProvidesStructuredLightingAndSeededCallTemplates() {
        seeded();val context=store.menuContext()
        val noise=ArtStudioMenuCatalog.filters(context,"filter.random_noise").getJSONArray("filters").getJSONObject(0)
        val args=noise.getJSONObject("calls").getJSONObject("preview").getJSONObject("parameters")
        assertEquals(0,args.getJSONObject("parameters").getInt("seed"))
        val phong=ArtStudioMenuCatalog.filters(context,"filter.phong_bumpmap").getJSONArray("filters").getJSONObject(0)
        val lights=phong.getJSONObject("calls").getJSONObject("apply").getJSONObject("parameters").getJSONObject("parameters").getJSONArray("lights")
        assertEquals(1,lights.length());assertEquals(45,lights.getJSONObject(0).getInt("elevation"))
    }
}
