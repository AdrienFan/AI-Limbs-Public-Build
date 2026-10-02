package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtCropTest {
    private fun request(x:Int=0,y:Int=0,w:Int=100,h:Int=100)=JSONObject().put("x",x).put("y",y).put("width",w).put("height",h)
    private fun rejects(block:()->Unit) {
        try {block();fail("Expected explicit rejection")} catch(expected:IllegalArgumentException) { }
    }
    @Test fun growthAllowsNegativeOriginButBoundedModeRejectsIt() {
        val p=request(-40,-20,240,180)
        assertEquals(-40,ArtCrop.resolve(p,200,150).getInt("x"))
        rejects {ArtCrop.resolve(JSONObject(p.toString()).put("allowGrow",false),200,150)}
    }
    @Test fun frameAndIncompatibleLocksAreExplicitlyRejected() {
        rejects {ArtCrop.resolve(request().put("target","frame"),200,200)}
        rejects {ArtCrop.resolve(request().put("lockWidth",true).put("lockRatio",true),200,200)}
        assertFalse(ArtCrop.info().getBoolean("frameAvailable"))
    }
    @Test fun lockedSizeKeepsRequestedCenter() {
        val p=ArtCrop.resolve(request(40,30,80,60).put("fromCenter",true).put("lockWidth",true).put("fixedWidth",40),300,300)
        assertEquals(60,p.getInt("x"));assertEquals(30,p.getInt("y"));assertEquals(40,p.getInt("width"))
    }
    @Test fun ratioPlanIsIdempotentAfterIntegerQuantization() {
        val p=ArtCrop.resolve(request(w=100,h=40).put("lockRatio",true).put("ratio",1.5),300,300)
        assertEquals(100,p.getInt("width"));assertEquals(67,p.getInt("height"))
        val twice=ArtCrop.resolve(p,300,300)
        assertEquals(ArtCrop.rect(p),ArtCrop.rect(twice))
    }
    @Test fun oversizeRatioDragFitsBothEdgesTogether() {
        val p=ArtCrop.defaults().put("lockRatio",true).put("ratio",2.0)
        val grown=ArtCrop.drag(null,"create",0.0,0.0,40000.0,20000.0,p,1000,1000)
        assertEquals(16384,grown.width);assertEquals(8192,grown.height)
        val bounded=ArtCrop.drag(null,"create",0.0,0.0,40000.0,20000.0,p.put("allowGrow",false),1000,800)
        assertEquals(1000,bounded.width);assertEquals(500,bounded.height)
    }
    @Test fun movingDoesNotResizeAndFitsInsideCanvas() {
        val rect=ArtCrop.drag(ArtCrop.Rect(10,10,100,80),"move",20.0,20.0,500.0,500.0,ArtCrop.defaults().put("allowGrow",false),200,150)
        assertEquals(ArtCrop.Rect(100,70,100,80),rect)
    }
    @Test fun northWestHandlePreservesOppositeCorner() {
        val rect=ArtCrop.drag(ArtCrop.Rect(20,30,100,80),"nw",20.0,30.0,0.0,10.0,ArtCrop.defaults(),300,300)
        assertEquals(ArtCrop.Rect(0,10,120,100),rect)
    }
    @Test fun invalidNumbersAndEdgesAreRejectedRatherThanTruncated() {
        rejects {ArtCrop.resolve(request().put("x",1.25),200,200)}
        rejects {ArtCrop.resolve(request(w=0),200,200)}
        rejects {ArtCrop.resolve(request().put("ratio",1e300),200,200)}
    }
    @Test fun guidesUseAbsoluteDocumentCoordinates() {
        val lines=ArtCrop.lines(ArtCrop.Rect(-30,20,90,60),"thirds")
        assertEquals(4,lines.size);assertEquals(0.0,lines[0][0],0.0001)
        assertEquals(20.0,lines[0][1],0.0001);assertEquals(80.0,lines[0][3],0.0001)
        assertTrue(ArtCrop.lines(ArtCrop.Rect(0,0,10,10),"none").isEmpty())
    }
    @Test fun repeatedLayerCropIntersectsAndNeverResurrectsEmptyLayer() {
        val original=listOf(0.0 to 0.0,100.0 to 0.0,100.0 to 100.0,0.0 to 100.0)
        val boundary=listOf(40.0 to -10.0,120.0 to -10.0,120.0 to 60.0,40.0 to 60.0)
        val result=ArtCrop.intersect(original,boundary)
        assertTrue(result.all {it.first in 40.0..100.0 && it.second in 0.0..60.0})
        assertEquals(4,result.size)
        assertTrue(ArtCrop.intersect(emptyList(),boundary).isEmpty())
        assertTrue(ArtCrop.intersect(original,boundary.map {(x,y)->(x+300) to y}).isEmpty())
    }
}
