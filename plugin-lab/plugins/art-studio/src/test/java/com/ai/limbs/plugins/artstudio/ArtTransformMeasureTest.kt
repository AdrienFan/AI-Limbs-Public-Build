package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure-math cases for cloud JUnit. Android Matrix, renderer and gestures require device checks. */
class ArtTransformMeasureTest {
    private fun line(x:Double=300.0,y:Double=0.0)=JSONObject().put("x0",0).put("y0",0).put("x1",x).put("y1",y)
    private fun rejects(block:()->Unit){try{block();fail("Expected explicit rejection")}catch(expected:IllegalArgumentException){}}
    private val square=listOf(ArtTransform.V(0.0,0.0),ArtTransform.V(100.0,0.0),ArtTransform.V(100.0,100.0),ArtTransform.V(0.0,100.0))
    private fun near(a:ArtTransform.V,b:ArtTransform.V){assertEquals(a.x,b.x,1e-6);assertEquals(a.y,b.y,1e-6)}
    @Test fun physicalMeasurementUsesExplicitPpi(){val r=ArtMeasure.evaluate(line(),ArtMeasure.defaults().put("unit","mm").put("ppi",300));assertEquals(25.4,r.getDouble("distance"),1e-6);assertEquals("toolPpi",r.getString("resolutionSource"))}
    @Test fun baselineReportsSignedAndAcuteAngles(){val r=ArtMeasure.evaluate(line(),ArtMeasure.defaults().put("baseline",30));assertEquals(-30.0,r.getDouble("relativeDegrees"),1e-6);assertEquals(30.0,r.getDouble("acuteDegrees"),1e-6)}
    @Test fun angleSnapPreservesLength(){val r=ArtMeasure.evaluate(line(100.0,25.0),ArtMeasure.defaults().put("baseline",10).put("angleStep",15));assertEquals(10.0,r.getDouble("degrees"),1e-6);assertEquals(kotlin.math.hypot(100.0,25.0),r.getDouble("distancePx"),1e-6)}
    @Test fun translationMovesBothEndpoints(){val r=ArtMeasure.evaluate(line().put("dx",20).put("dy",-8),ArtMeasure.defaults());assertEquals(20.0,r.getDouble("x0"),1e-6);assertEquals(320.0,r.getDouble("x1"),1e-6);assertEquals(-8.0,r.getDouble("y0"),1e-6);assertEquals(300.0,r.getDouble("distancePx"),1e-6)}
    @Test fun zeroLengthHasExplicitDegenerateResult(){val r=ArtMeasure.evaluate(line(0.0,0.0),ArtMeasure.defaults());assertTrue(r.getBoolean("degenerate"));assertEquals(0.0,r.getDouble("relativeDegrees"),0.0)}
    @Test fun anglesWrapAtHalfTurn(){assertEquals(-180.0,ArtMeasure.normalize(180.0),0.0);assertEquals(10.0,ArtMeasure.normalize(730.0),0.0)}
    @Test fun invalidUnitsAndPpiAreRejected(){rejects{ArtMeasure.settings(ArtMeasure.defaults(),JSONObject().put("unit","yards"))};rejects{ArtMeasure.settings(ArtMeasure.defaults(),JSONObject().put("ppi",0))}}
    @Test fun measurementDoesNotMutateResourceSettings(){val o=ArtMeasure.defaults();val before=o.toString();ArtMeasure.evaluate(line().put("unit","mm").put("ppi",300),o);assertEquals(before,o.toString())}
    @Test fun warpReproducesGeneralAffineMapping(){val targets=square.map{ArtTransform.V(1.5*it.x+.2*it.y+7,.8*it.y-3)};val v=ArtTransform.V(38.0,42.0);near(ArtTransform.V(1.5*v.x+.2*v.y+7,.8*v.y-3),ArtTransform.mls(v,square,targets,1.0))}
    @Test fun warpHitsItsControlPointsExactly(){val q=square.map{it+ArtTransform.V(3.0,7.0)};near(q[2],ArtTransform.mls(square[2],square,q,1.0))}
    @Test fun collinearWarpControlsAreRejected(){val p=listOf(ArtTransform.V(0.0,0.0),ArtTransform.V(20.0,0.0),ArtTransform.V(40.0,0.0));rejects{ArtTransform.mls(ArtTransform.V(10.0,10.0),p,p,1.0)}}
    @Test fun cageReproducesAffineMapping(){val q=square.map{ArtTransform.V(it.x*2+8,it.y*.5-5)};near(ArtTransform.V(58.0,15.0),ArtTransform.mvc(ArtTransform.V(25.0,40.0),square,q))}
    @Test fun cageBoundaryUsesEdgeInterpolation(){val q=square.map{it+ArtTransform.V(4.0,8.0)};near(ArtTransform.V(54.0,8.0),ArtTransform.mvc(ArtTransform.V(50.0,0.0),square,q))}
    private fun dab(kind:String)=JSONObject().put("kind",kind).put("x",50).put("y",50).put("radius",40).put("strength",.5)
    @Test fun liquifyPushHasCompactSupport(){val p=dab("push").put("dx",10).put("dy",0);near(ArtTransform.V(55.0,50.0),ArtTransform.dab(ArtTransform.V(50.0,50.0),p));near(ArtTransform.V(100.0,100.0),ArtTransform.dab(ArtTransform.V(100.0,100.0),p))}
    @Test fun liquifyExpandAndContractMoveOppositeDirections(){val v=ArtTransform.V(60.0,50.0);assertTrue(ArtTransform.dab(v,dab("expand")).x>60);assertTrue(ArtTransform.dab(v,dab("contract")).x<60)}
    @Test fun liquifyTwirlPreservesRadialDistance(){val v=ArtTransform.dab(ArtTransform.V(60.0,50.0),dab("twirl").put("angle",90));assertEquals(10.0,kotlin.math.hypot(v.x-50,v.y-50),1e-6)}
    @Test fun invalidOffImageDabCannotDisappearAsNoOp(){rejects{ArtTransform.dab(ArtTransform.V(1000.0,1000.0),dab("wrong"))};rejects{ArtTransform.dab(ArtTransform.V(1000.0,1000.0),dab("push").put("dx",41))}}
}
