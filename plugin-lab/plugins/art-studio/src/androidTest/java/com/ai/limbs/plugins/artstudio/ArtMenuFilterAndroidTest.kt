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
        val undone=store.history("AWEI",true)
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
}
