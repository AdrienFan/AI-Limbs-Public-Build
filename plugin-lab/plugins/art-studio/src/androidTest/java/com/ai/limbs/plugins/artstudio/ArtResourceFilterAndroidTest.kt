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
class ArtResourceFilterAndroidTest {
    private lateinit var directory:File
    private lateinit var store:ArtStore
    @Before fun prepare() {
        directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"resource-filters-"+UUID.randomUUID())
        store=ArtStore(directory)
    }
    @After fun clean() {directory.deleteRecursively()}
    private fun request(s:JSONObject)=JSONObject().put("documentId",s.getString("id")).put("expectedRevision",s.getInt("revision"))
    private fun layer(s:JSONObject)=s.getJSONObject("state").getString("selectedLayerId")
    private fun fill(s:JSONObject)=store.encloseFill("AWEI",request(s).put("layerId",layer(s)).put("shape","rect")
        .put("points",JSONArray("[[0,0],[2,2]]")).put("mode","all").put("includeContour",true).put("antialias",0)
        .put("color","#FFFFFFFF").put("linearRGBA",JSONArray("[2,0.2,-1,0.5]")))
    private fun seeded()=fill(store.create(8,8,"#00000000","hidden color regression"))
    private fun pixels(owner:ArtStore,s:JSONObject):FloatArray {
        val b=ArtRenderer.render(owner,s);return try {ArtFloatPixels.read(b)}finally {b.recycle()}
    }
    private fun hidden(owner:ArtStore,s:JSONObject)=owner.filterHidden(request(s).put("layerId",layer(s)).put("x",6).put("y",6))
    private fun preserve(s:JSONObject)=store.executeMenu("LANER","filter.propagate_colors",request(s).put("alphaMode","preserve"))

    @Test fun hiddenPreviewIsReadOnlyAndCommitUndoRedoKeepVisiblePixelsIdentical() {
        val s=seeded();val before=pixels(store,s);val assets=File(directory,"assets").list()?.toSet()
        val preview=store.previewMenuBitmap("filter.propagate_colors",request(s).put("alphaMode","preserve"));preview.recycle()
        assertEquals(s.getInt("revision"),store.current().getInt("revision"));assertEquals(assets,File(directory,"assets").list()?.toSet())
        val changed=preserve(s);assertEquals(s.getInt("revision")+1,changed.getInt("revision"))
        assertArrayEquals(before,pixels(store,changed),0f)
        val sample=hidden(store,changed);assertTrue(sample.getBoolean("hasHiddenColor"))
        assertEquals(2.0,sample.getJSONArray("linearRGB").getDouble(0),.002)
        val undone=store.history("LANER",redo=false);assertFalse(hidden(store,undone).getBoolean("hasHiddenColor"))
        val restored=store.history("LANER",redo=true);assertTrue(hidden(store,restored).getBoolean("hasHiddenColor"))
        assertArrayEquals(before,pixels(store,restored),0f)
    }
    @Test fun hiddenAssetSurvivesArchiveRemappingAndCanBeRevealedAfterImport() {
        val s=preserve(seeded());val saved=store.save();val copy=ArtStore(File(directory,"copy"))
        val imported=copy.importArchive(File(saved.getString("path")).readBytes())
        assertNotEquals(s.getString("id"),imported.getString("id"));assertTrue(hidden(copy,imported).getBoolean("hasHiddenColor"))
        assertArrayEquals(pixels(store,s),pixels(copy,imported),0f)
        val revealed=copy.executeMenu("AWEI","filter.propagate_colors",request(imported).put("useHiddenSeeds",true))
        val out=pixels(copy,revealed);assertEquals(1f,out[(6*8+6)*4+3],0f);assertEquals(2f,out[(6*8+6)*4],.002f)
    }
    @Test fun hiddenColorsBelongToIndividualCelsAndBlankFramesHaveNone() {
        var s=preserve(seeded());val id=layer(s)
        s=store.animationKey("AWEI",request(s).put("layerId",id).put("frame",5).put("action","blank"))
        s=store.animationSeek("AWEI",request(s).put("frame",5))
        assertFalse(hidden(store,s).getBoolean("hasHiddenColor"))
        s=store.animationSeek("AWEI",request(s).put("frame",0))
        assertTrue(hidden(store,s).getBoolean("hasHiddenColor"))
    }
    @Test fun ordinaryPixelEditsInvalidateCurrentHiddenPlaneAndUndoRestoresIt() {
        val s=preserve(seeded());assertTrue(hidden(store,s).getBoolean("hasHiddenColor"))
        val changed=fill(s);assertFalse(hidden(store,changed).getBoolean("hasHiddenColor"))
        val undone=store.history("AWEI",redo=false);assertTrue(hidden(store,undone).getBoolean("hasHiddenColor"))
    }
    @Test fun selectionRestrictsHiddenWriteWithoutRestrictingSeedRead() {
        val source=floatArrayOf(1f,0f,0f,1f,0f,0f,0f,0f,0f,0f,0f,0f)
        val bitmap=ArtFloatPixels.from(3,1,source)
        try {
            val selection=JSONObject().put("shape","rect").put("x",2).put("y",0).put("width",1).put("height",1)
            val plane=requireNotNull(ArtMenuOperations.filter(bitmap,"filter.propagate_colors",JSONObject().put("alphaMode","preserve"),selection))
            assertEquals(0f,plane[7],0f);assertEquals(1f,plane[11],0f);assertEquals(1f,plane[8],0f)
            assertArrayEquals(source,ArtFloatPixels.read(bitmap),0f)
        } finally {bitmap.recycle()}
    }
    @Test fun referenceStatsAreReadOnlyVersionGuardedAndSavedAsValues() {
        val s=seeded();val assets=File(directory,"assets").list()?.toSet()
        val stats=store.filterReference(request(s).put("layerId",layer(s))).getJSONObject("referenceStats")
        assertEquals(s.getInt("revision"),store.current().getInt("revision"));assertEquals(assets,File(directory,"assets").list()?.toSet())
        val changed=store.executeMenu("AWEI","filter.color_transfer",request(s).put("referenceStats",stats))
        assertEquals(stats.toString(),changed.getJSONObject("state").getJSONObject("lastFilter").getJSONObject("parameters").getJSONObject("referenceStats").toString())
        assertThrows(IllegalArgumentException::class.java) {store.filterReference(request(s).put("layerId",layer(s)))}
    }
    @Test fun workspacePaletteEditsCannotChangeFrozenFilterAndAllFiveAreDiscoverable() {
        val s=seeded()
        val workspace=store.saveColorPalette(JSONObject().put("name","fixture").put("colors",JSONArray("[\"#FF000000\",\"#FFFFFFFF\"]")))
        val palette=workspace.getJSONArray("palettes").getJSONObject(0)
        val values=store.filterResources().getJSONArray("palettes").getJSONObject(0).getJSONArray("colors")
        val changed=store.executeMenu("AWEI","filter.palettize",request(s).put("palette",values))
        store.saveColorPalette(JSONObject().put("id",palette.getString("id")).put("name","fixture").put("colors",JSONArray("[\"#FFFF0000\",\"#FF00FF00\"]")))
        val repeated=store.executeMenu("AWEI","filter_apply_again",request(changed))
        assertEquals(values.toString(),repeated.getJSONObject("state").getJSONObject("lastFilter").getJSONObject("parameters").getJSONArray("palette").toString())
        val context=store.menuContext()
        for(id in ArtResourceFilters.ids) {
            val entry=ArtStudioMenuCatalog.filters(context,id).getJSONArray("filters").getJSONObject(0)
            assertTrue(entry.getBoolean("implemented"));assertTrue(entry.getBoolean("enabled"));assertTrue(entry.has("calls"))
        }
    }
}
