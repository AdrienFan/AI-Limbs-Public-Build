package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure prefilter/cleanup and settings lifecycle cases for later cloud execution. */
class ArtColorizeFiltersTest {
    @Test fun disabledFiltersPreserveOldUnsignedHeightBytes() {
        val input=byteArrayOf(0,64,127,180.toByte(),255.toByte())
        assertArrayEquals(input,ArtColorizeFilters.height(input,5,1,false,4.0,0.0))
        assertArrayEquals(input,ArtColorizeFilters.height(input,5,1,true,0.0,0.0))
    }
    @Test fun replicatedBordersPreserveConstantsEvenWhenRadiusExceedsImageSize() {
        val result=ArtColorizeFilters.blur(FloatArray(6) {0.25f},2,3,500.0)
        result.forEach {assertEquals(0.25f,it,1e-6f)}
    }
    @Test fun fractionalRadiusReallyBlursAndDoesNotRoundDownToZero() {
        val input=FloatArray(9);input[4]=1f
        val narrow=ArtColorizeFilters.blur(input,9,1,0.5)
        val wide=ArtColorizeFilters.blur(input,9,1,2.0)
        assertTrue(narrow[3]>0f);assertTrue(narrow[4]<1f);assertTrue(wide[4]<narrow[4])
        assertEquals(narrow[3],narrow[5],1e-6f);assertEquals(1f,narrow.sum(),1e-6f)
    }
    @Test fun shadowEdgesLeaveDeepSolidInteriorsOpen() {
        val input=ByteArray(41*41)
        for(y in 5..35)for(x in 5..35)input[y*41+x]=255.toByte()
        val edges=ArtColorizeFilters.height(input,41,41,true,1.0,0.0)
        assertEquals(0,edges[20*41+20].toInt() and 255)
        assertTrue((edges[20*41+5].toInt() and 255)>100)
        assertArrayEquals(ByteArray(25),ArtColorizeFilters.height(ByteArray(25),5,5,true,4.0,3.0))
    }
    @Test fun fuzzyRidgesIncreaseGapResistanceAndKeepOriginalLinePeaks() {
        val input=ByteArray(49)
        for(y in 1..5)if(y!=3)input[y*7+3]=255.toByte()
        val result=ArtColorizeFilters.height(input,7,7,false,4.0,1.5)
        assertTrue((result[3*7+3].toInt() and 255)>0)
        assertEquals(255,result[2*7+3].toInt() and 255)
    }
    @Test fun cleanupCanIgnoreSpillSeedsWithoutEditingSavedSeedLabels() {
        val labels=IntArray(25) {0};labels[12]=1
        val seeds=IntArray(25) {-1};seeds[0]=0;seeds[12]=1;val before=seeds.copyOf()
        val cleaned=ArtColorizeFilters.cleanup(labels,seeds,5,5,0.7)
        assertEquals(1,cleaned.pixels);assertEquals(1,cleaned.seedPixels);assertEquals(1,cleaned.regions)
        assertEquals(0,labels[12]);assertArrayEquals(before,seeds)
    }
    @Test fun higherStrengthCleansWeakerForeignBoundaryButZeroIsExactNoop() {
        val original=IntArray(45) {-1};for(x in 1..3)original[18+x]=1;for(x in 4..7)original[18+x]=0
        val seeds=IntArray(45) {-1};seeds[19]=1;seeds[25]=0
        val none=original.copyOf();assertEquals(0,ArtColorizeFilters.cleanup(none,seeds,9,5,0.0).pixels)
        assertArrayEquals(original,none)
        assertEquals(0,ArtColorizeFilters.cleanup(original.copyOf(),seeds,9,5,0.7).pixels)
        assertEquals(3,ArtColorizeFilters.cleanup(original.copyOf(),seeds,9,5,1.0).pixels)
    }
    @Test fun isolatedSingleColorAndBalancedCompetingRegionsStayUnchanged() {
        val single=IntArray(12) {0};assertEquals(0,ArtColorizeFilters.cleanup(single,IntArray(12) {-1},4,3,1.0).pixels)
        val balanced=IntArray(12) {if(it%4<2)0 else 1};val before=balanced.copyOf()
        assertEquals(0,ArtColorizeFilters.cleanup(balanced,IntArray(12) {-1},4,3,1.0).pixels)
        assertArrayEquals(before,balanced)
    }
    @Test fun oldSettingsResolveToDisabledDefaultsAndKeepFractionalNewValues() {
        val legacy=JSONObject("{\"threshold\":180,\"gapClose\":1,\"limitBounds\":false,\"editKeys\":true,\"showOutput\":true}")
        val resolved=ArtColorize.normalizeSettings(legacy,JSONObject())
        assertFalse(resolved.getBoolean("useEdgeDetection"));assertEquals(0.0,resolved.getDouble("fuzzyRadius"),0.0)
        val changed=ArtColorize.normalizeSettings(resolved,JSONObject("{\"edgeDetectionSize\":2.5,\"fuzzyRadius\":3.25,\"cleanUpAmount\":0.7}"))
        assertEquals(3.25,changed.getDouble("fuzzyRadius"),0.0);assertEquals(1,changed.getInt("gapClose"))
    }
    @Test fun filterChangesInvalidateCachedOutputWhileDisplayChangesDoNot() {
        val id="11111111-1111-1111-1111-111111111111"
        val data=JSONObject().put("generation",4).put("settings",ArtColorize.settings())
        val layer=JSONObject().put("id",id).put("kind","colorize").put("locked",false).put("colorize",data)
        val state=JSONObject().put("layers",org.json.JSONArray().put(layer))
        fun edit(settings:JSONObject)=ArtColorize.edit(state,"COLORIZE_SETTINGS",JSONObject().put("maskId",id).put("settings",settings))
        edit(JSONObject().put("showOutput",false));assertEquals(4,data.getInt("generation"))
        edit(JSONObject().put("useEdgeDetection",true));assertEquals(5,data.getInt("generation"))
        edit(JSONObject().put("useEdgeDetection",true));assertEquals(5,data.getInt("generation"))
        edit(JSONObject().put("cleanUpAmount",0.7));assertEquals(6,data.getInt("generation"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectNumericStrings() {ArtColorize.normalizeSettings(ArtColorize.settings(),JSONObject().put("fuzzyRadius","3"))}
    @Test(expected=IllegalArgumentException::class)
    fun rejectOutOfRangeCleanup() {ArtColorize.normalizeSettings(ArtColorize.settings(),JSONObject().put("cleanUpAmount",1.01))}
}
