package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Geometry contracts for later cloud execution; no Android Canvas or local test run. */
class ArtAssistantGeometryTest {
    private fun point(x:Double,y:Double)=AssistantPoint(x,y)
    private fun assistant(type:String,vararg points:AssistantPoint):JSONObject = ArtAssistants.normalize(
        JSONObject().put("id","11111111-1111-1111-1111-111111111111").put("type",type)
            .put("points",JSONArray(points.map {it.json()})))
    private fun close(a:AssistantPoint,b:AssistantPoint,tolerance:Double=1e-5) {
        assertEquals(a.x,b.x,tolerance);assertEquals(a.y,b.y,tolerance)
    }
    private val square=listOf(point(0.0,0.0),point(200.0,0.0),point(200.0,200.0),point(0.0,200.0))
    @Test fun homographyMapsAllCornersAndRoundTripsInteriorInEitherWinding() {
        val points=listOf(point(20.0,20.0),point(280.0,40.0),point(220.0,220.0),point(40.0,200.0))
        for(h in listOf(points,points.reversed())) {
            val q=ArtAssistantGeometry.Quad(h)
            listOf(point(0.0,0.0),point(1.0,0.0),point(1.0,1.0),point(0.0,1.0)).forEachIndexed {i,p->close(h[i],q.at(p.x,p.y))}
            close(point(0.31,0.72),q.uv(q.at(0.31,0.72)))
            assertTrue(q.contains(q.at(0.31,0.72)));assertFalse(q.contains(point(-100.0,-100.0)))
        }
        assertFalse(ArtAssistantGeometry.Quad.valid(listOf(square[0],square[2],square[1],square[3])))
        assertFalse(ArtAssistantGeometry.Quad.valid(listOf(square[0],square[1],point(100.0,0.0),square[3])))
    }
    @Test fun perspectiveGridLocksTheInitialFamilyAndRequiresAnInteriorOrigin() {
        val a=assistant("perspective_grid",*square.toTypedArray());val start=point(100.0,100.0)
        val projection=ArtAssistants.Projection(a,start)
        close(point(170.0,100.0),projection.project(point(170.0,105.0)))
        close(point(105.0,100.0),projection.project(point(105.0,190.0)))
        close(point(400.0,100.0),projection.project(point(400.0,350.0))) // Family extends beyond the quadrilateral after locking.
        assertFalse(ArtAssistantGeometry.eligible(a,point(220.0,100.0)))
    }
    @Test fun perspectiveEllipseProjectionLivesOnTheMappedUnitCircle() {
        val a=assistant("perspective_ellipse",*arrayOf(point(20.0,20.0),point(280.0,40.0),point(220.0,220.0),point(40.0,200.0)))
        val q=ArtAssistantGeometry.Quad(ArtAssistants.points(a));val p=ArtAssistants.Projection(a,point(150.0,150.0))
        for(sample in listOf(point(70.0,20.0),point(180.0,170.0),point(300.0,120.0))) {
            val uv=q.uv(p.project(sample));assertEquals(0.25,(uv.x-0.5).pow(2)+(uv.y-0.5).pow(2),1e-6)
        }
    }
    @Test fun dualVanishingPointsLockBranchesAndCanDisableVerticalDirection() {
        val a=assistant("two_vanishing_points",point(0.0,0.0),point(200.0,0.0),point(100.0,80.0))
        val start=point(100.0,100.0);val vertical=ArtAssistants.Projection(a,start)
        close(point(100.0,160.0),vertical.project(point(103.0,160.0)))
        close(point(100.0,80.0),vertical.project(point(180.0,80.0)))
        a.put("useVertical",false);val diagonal=ArtAssistants.Projection(a,start)
        val out=diagonal.project(point(150.0,160.0));assertEquals(out.x,out.y,1e-6)
        assertFalse(ArtAssistantGeometry.eligible(a,point(0.0,0.0)))
    }
    @Test fun curvilinearCirclePassesThroughBothVanishingPointsAndTheOrigin() {
        val a=assistant("curvilinear_perspective",point(0.0,0.0),point(200.0,0.0));val start=point(90.0,80.0)
        val c=requireNotNull(ArtAssistantGeometry.circle(point(0.0,0.0),point(200.0,0.0),start))
        for(p in listOf(point(0.0,0.0),point(200.0,0.0),start))assertEquals(c.radius,(p-c.center).length(),1e-6)
        val projection=ArtAssistants.Projection(a,start)
        close(start,projection.project(start))
        assertEquals(c.radius,(projection.project(point(120.0,180.0))-c.center).length(),1e-6)
        val horizon=ArtAssistants.Projection(a,point(50.0,0.0));close(point(50.0,0.0),horizon.project(point(50.0,100.0)))
    }
    @Test fun fisheyeUsesTheOriginEllipseIncludingAdjacentAxisIntervals() {
        val a=assistant("fisheye",point(0.0,0.0),point(200.0,0.0),point(100.0,80.0))
        for(start in listOf(point(100.0,50.0),point(300.0,50.0),point(-100.0,50.0))) {
            val p=ArtAssistants.Projection(a,start);close(start,p.project(start),1e-4)
            val out=p.project(point(start.x+70.0,90.0))
            assertEquals(1.0,((out.x-start.x)/100.0).pow(2)+(out.y/50.0).pow(2),1e-5)
        }
        assertFalse(ArtAssistantGeometry.eligible(a,point(200.0,50.0)))
    }
    @Test fun splineEndpointOrderAndTrackingResetAreStable() {
        val h=listOf(point(0.0,100.0),point(200.0,100.0),point(60.0,0.0),point(140.0,200.0))
        close(h[0],ArtAssistantGeometry.spline(h,0.0));close(h[1],ArtAssistantGeometry.spline(h,1.0))
        val a=assistant("spline",*h.toTypedArray());val p=ArtAssistants.Projection(a,h[0])
        for(i in 0..20) {val on=ArtAssistantGeometry.spline(h,i/20.0);close(on,p.project(on),1e-4)}
        p.resetTracking();close(h[0],p.project(h[0]),1e-4)
    }
    @Test fun localBoundsGateOnlyTheOriginAndSnapSessionExcludesOutsideOrigins() {
        val a=assistant("parallel_ruler",point(0.0,0.0),point(100.0,0.0))
            .put("localEnabled",true).put("localBounds",JSONObject().put("x",0).put("y",0).put("width",100).put("height",100))
        assertTrue(ArtAssistantGeometry.eligible(a,point(100.0,100.0)))
        assertFalse(ArtAssistantGeometry.eligible(a,point(100.1,50.0)))
        val p=ArtAssistants.Projection(a,point(50.0,50.0));close(point(300.0,50.0),p.project(point(300.0,200.0)))
        val state=JSONObject().put("assistants",JSONArray().put(a)).put("selectedAssistantId",a.getString("id"))
        val outside=ArtAssistants.SnapSession(state,point(150.0,50.0),16.0)
        val raw=listOf(point(150.0,50.0),point(250.0,100.0));assertEquals(raw,outside.complete(raw));assertNull(outside.active)
    }
    @Test fun physicalUnitsAndFixedLengthSurviveJsonAndDirectionEdits() {
        assertEquals(300.0,ArtAssistantGeometry.pixels(25.4,"mm",300.0),1e-8)
        assertEquals(300.0,ArtAssistantGeometry.pixels(2.54,"cm",300.0),1e-8)
        assertEquals(300.0,ArtAssistantGeometry.pixels(1.0,"in",300.0),1e-8)
        assertEquals(300.0,ArtAssistantGeometry.pixels(72.0,"pt",300.0),1e-8)
        val a=assistant("ruler",point(10.0,10.0),point(13.0,14.0)).put("fixedLength",25.4).put("lengthUnit","mm").put("unitDpi",300)
        val normalized=ArtAssistants.normalize(a);assertEquals(300.0,(ArtAssistants.points(normalized)[1]-ArtAssistants.points(normalized)[0]).length(),1e-8)
        normalized.put("points",JSONArray().put(point(10.0,10.0).json()).put(point(10.0,20.0).json()))
        val edited=ArtAssistants.normalize(JSONObject(normalized.toString()))
        close(point(10.0,310.0),ArtAssistants.points(edited)[1]);assertEquals("mm",edited.getString("lengthUnit"))
    }
    @Test fun allTypeExamplesAreValidAndDescribeExactlyTheirHandleCounts() {
        val info=ArtAssistants.typeInfo()
        assertEquals(12,ArtAssistants.types.size)
        for(type in ArtAssistants.types.keys) {
            val spec=info.getJSONObject(type)
            assertEquals(ArtAssistants.count(type),spec.getJSONArray("examplePoints").length())
            ArtAssistants.normalize(JSONObject().put("id","11111111-1111-1111-1111-111111111111")
                .put("type",type).put("points",spec.getJSONArray("examplePoints")))
        }
        assertTrue(ArtCapabilityHelp.parameterDescription("assistant.create","points").contains("样条4点"))
        assertEquals("perspective_grid",ArtCapabilityHelp.example("assistant.create").getString("type"))
    }
    @Test(expected=IllegalArgumentException::class) fun localAreaNeedsAnExplicitRectangle() {
        ArtAssistants.normalize(assistant("ruler",point(0.0,0.0),point(100.0,0.0)).put("localEnabled",true))
    }
    @Test(expected=IllegalArgumentException::class) fun fixedLengthIsRejectedForNonRulers() {
        ArtAssistants.normalize(assistant("curvilinear_perspective",point(0.0,0.0),point(100.0,0.0)).put("fixedLength",100))
    }
}
