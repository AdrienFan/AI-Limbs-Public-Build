package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.*

/** Independent document-space geometry; no Android or Krita runtime. */
internal object ArtAssistantGeometry {
    val advanced=setOf("spline","perspective_grid","perspective_ellipse","two_vanishing_points","fisheye","curvilinear_perspective")
    val units=linkedMapOf("px" to "像素","mm" to "毫米","cm" to "厘米","in" to "英寸","pt" to "点")
    fun pixels(value:Double,unit:String,dpi:Double):Double {
        require(value.isFinite()&&value>=0&&dpi.isFinite()&&dpi in 1.0..2400.0&&unit in units)
        return value*when(unit) {"px"->1.0;"mm"->dpi/25.4;"cm"->dpi/2.54;"in"->dpi;"pt"->dpi/72;else->error("单位无效")}
    }
    fun localEligible(a:JSONObject,p:AssistantPoint):Boolean {
        if(!a.optBoolean("localEnabled",false))return true
        val b=a.getJSONObject("localBounds")
        return p.x>=b.getDouble("x")&&p.y>=b.getDouble("y")&&p.x<=b.getDouble("x")+b.getDouble("width")&&p.y<=b.getDouble("y")+b.getDouble("height")
    }
    private fun cross(a:AssistantPoint,b:AssistantPoint)=a.x*b.y-a.y*b.x
    class Quad(val p:List<AssistantPoint>) {
        companion object {
            private fun coefficients(p:List<AssistantPoint>):DoubleArray? {
                if(p.size!=4)return null
                val edges=p.indices.map {p[(it+1)%4]-p[it]};val scale=edges.maxOf {it.length()}
                if(!scale.isFinite()||scale<0.01||edges.any {it.length()<0.01})return null
                val turns=p.indices.map {cross(edges[it],edges[(it+1)%4])/(scale*scale)}
                if(!(turns.all {it>1e-8}||turns.all {it< -1e-8}))return null
                val dx1=p[1].x-p[2].x;val dx2=p[3].x-p[2].x;val dx3=p[0].x-p[1].x+p[2].x-p[3].x
                val dy1=p[1].y-p[2].y;val dy2=p[3].y-p[2].y;val dy3=p[0].y-p[1].y+p[2].y-p[3].y
                val det=dx1*dy2-dx2*dy1
                if(abs(det)<=1e-8*scale*scale)return null
                val g=(dx3*dy2-dx2*dy3)/det;val h=(dx1*dy3-dx3*dy1)/det
                if(!listOf(1.0,1+g,1+h,1+g+h).all {it>1e-8})return null
                val m=doubleArrayOf(p[1].x-p[0].x+g*p[1].x,p[3].x-p[0].x+h*p[3].x,p[0].x,
                    p[1].y-p[0].y+g*p[1].y,p[3].y-p[0].y+h*p[3].y,p[0].y,g,h,1.0)
                val determinant=m[0]*(m[4]*m[8]-m[5]*m[7])+m[1]*(m[5]*m[6]-m[3]*m[8])+m[2]*(m[3]*m[7]-m[4]*m[6])
                return if(m.all {it.isFinite()}&&determinant.isFinite()&&abs(determinant)>1e-12)m else null
            }
            fun valid(p:List<AssistantPoint>)=coefficients(p)!=null
        }
        private val values=requireNotNull(coefficients(p)) {"透视四角须依次排列，构成可映射的非退化凸四边形"}
        private val inverse:DoubleArray
        init {
            val m=values;val inv=doubleArrayOf(m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
                m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
                m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3])
            val determinant=m[0]*inv[0]+m[1]*inv[3]+m[2]*inv[6]
            inverse=DoubleArray(9) {inv[it]/determinant}
        }
        private fun mapped(v:DoubleArray,u:Double,w:Double):AssistantPoint {
            val d=v[6]*u+v[7]*w+v[8];require(abs(d)>1e-12)
            return AssistantPoint((v[0]*u+v[1]*w+v[2])/d,(v[3]*u+v[4]*w+v[5])/d)
        }
        fun at(u:Double,v:Double)=mapped(values,u,v)
        fun uv(p:AssistantPoint)=mapped(inverse,p.x,p.y)
        fun contains(p:AssistantPoint):Boolean {
            var positive=false;var negative=false
            for(i in 0..3) {val c=cross(this.p[(i+1)%4]-this.p[i],p-this.p[i]);positive=positive||c>1e-7;negative=negative||c< -1e-7}
            return !(positive&&negative)
        }
        fun axes(p:AssistantPoint):List<AssistantPoint> = listOf(
            AssistantPoint(values[0]-values[6]*p.x,values[3]-values[6]*p.y),
            AssistantPoint(values[1]-values[7]*p.x,values[4]-values[7]*p.y))
    }
    fun spline(h:List<AssistantPoint>,t:Double):AssistantPoint {
        val u=1-t;return h[0]*(u*u*u)+h[2]*(3*u*u*t)+h[3]*(3*u*t*t)+h[1]*(t*t*t)
    }
    /** Global sampled bracket + golden minimization; subsequent spline points use a bounded parameter window. */
    fun nearest(curve:(Double)->AssistantPoint,p:AssistantPoint,previous:Double?=null):Pair<AssistantPoint,Double> {
        val lo=if(previous==null)0.0 else maxOf(0.0,previous-0.3);val hi=if(previous==null)1.0 else minOf(1.0,previous+0.3)
        fun distance(t:Double):Double {val d=curve(t)-p;return d.dot(d)}
        val samples=128;var best=lo;var value=distance(best);var bestIndex=0
        for(i in 1..samples) {val t=lo+(hi-lo)*i/samples;val d=distance(t);if(d<value) {best=t;value=d;bestIndex=i}}
        var a=lo+(hi-lo)*maxOf(0,bestIndex-1)/samples;var b=lo+(hi-lo)*minOf(samples,bestIndex+1)/samples
        val ratio=(sqrt(5.0)-1)/2;var x=b-ratio*(b-a);var y=a+ratio*(b-a);var dx=distance(x);var dy=distance(y)
        repeat(28) {if(dx<dy) {b=y;y=x;dy=dx;x=b-ratio*(b-a);dx=distance(x)}
            else {a=x;x=y;dx=dy;y=a+ratio*(b-a);dy=distance(y)}}
        val refined=(a+b)*0.5;if(distance(refined)<value)best=refined
        return curve(best) to best
    }
    data class Circle(val center:AssistantPoint,val radius:Double)
    fun circle(a:AssistantPoint,b:AssistantPoint,through:AssistantPoint):Circle? {
        val axis=(b-a)*(1/(b-a).length());val n=AssistantPoint(-axis.y,axis.x);val mid=(a+b)*0.5
        val d=through-mid;val u=d.dot(axis);val v=d.dot(n);val half=(b-a).length()*0.5
        if(abs(v)<=maxOf(1e-8,half*1e-6))return null // The collinear family is the straight horizon limit.
        val offset=(u*u+v*v-half*half)/(2*v);val center=mid+n*offset
        return Circle(center,hypot(half,offset))
    }
    fun eligible(a:JSONObject,start:AssistantPoint):Boolean {
        if(!localEligible(a,start))return false
        val h=ArtAssistants.points(a)
        return when(a.getString("type")) {
            "perspective_grid"->Quad(h).contains(start)
            "two_vanishing_points","curvilinear_perspective"->(start-h[0]).length()>=0.01&&(start-h[1]).length()>=0.01
            "fisheye"->{val length=(h[1]-h[0]).length();val axis=(h[1]-h[0])*(1/length)
                val d=start-h[0];val t=d.dot(axis)/length;val offset=t-floor(t)-0.5
                val height=abs(d.dot(AssistantPoint(-axis.y,axis.x)))
                (start-h[0]).length()>=0.01&&(start-h[1]).length()>=0.01&&
                    (height<=maxOf(1e-8,length*1e-6)||1-4*offset*offset>1e-10)}
            "vanishing_point"->(start-h[0]).length()>=0.01
            "concentric_ellipse"->(start-ArtAssistants.ellipse(a).center).length()>=0.01
            else->true
        }
    }
    class Projection(val a:JSONObject,val start:AssistantPoint) {
        private val type=a.getString("type");private val h=ArtAssistants.points(a)
        private val quad=if(type in setOf("perspective_grid","perspective_ellipse"))Quad(h) else null
        private var branch=-1;private var previous:Double?=null
        private val circle=if(type=="curvilinear_perspective")circle(h[0],h[1],start) else null
        private val fish:ArtAssistants.Ellipse?=if(type=="fisheye")run {
            val axis=(h[1]-h[0])*(1/(h[1]-h[0]).length());val normal=AssistantPoint(-axis.y,axis.x)
            val length=(h[1]-h[0]).length();val t=(start-h[0]).dot(axis)/length;val cell=floor(t)
            val center=h[0]+axis*(length*(cell+0.5));val d=start-center;val major=length*0.5
            val v=d.dot(normal);val divisor=1-d.dot(axis).pow(2)/major.pow(2)
            if(abs(v)<=maxOf(1e-8,length*1e-6))null
            else {require(divisor>1e-10) {"鱼眼请从轴段之间的侧方起笔"};ArtAssistants.Ellipse(center,axis,normal,major,abs(v)/sqrt(divisor))}
        } else null
        fun resetTracking() {previous=null}
        fun project(p:AssistantPoint):AssistantPoint = when(type) {
            "spline"->{val next=nearest({t->spline(h,t)},p,previous);previous=next.second;next.first}
            "perspective_ellipse"->nearest({t->requireNotNull(quad).at(0.5+0.5*cos(2*PI*t),0.5+0.5*sin(2*PI*t))},p).first
            "perspective_grid","two_vanishing_points"->{
                val axes=if(type=="perspective_grid")requireNotNull(quad).axes(start) else buildList {
                    add(h[0]-start);add(h[1]-start)
                    if(a.optBoolean("useVertical",true)) {val axis=h[1]-h[0];add(AssistantPoint(-axis.y,axis.x))}
                }
                if(branch<0&&(p-start).length()>=0.01)branch=axes.indices.minBy {i->(ArtAssistants.line(p,start,start+axes[i])-p).length()}
                if(branch<0)start else ArtAssistants.line(p,start,start+axes[branch])
            }
            "curvilinear_perspective"->{val c=circle
                if(c==null)ArtAssistants.line(p,h[0],h[1]) else {val d=p-c.center;val direction=if(d.length()<1e-12)start-c.center else d
                    c.center+direction*(c.radius/direction.length())}}
            "fisheye"->{val e=fish
                if(e==null)ArtAssistants.line(p,h[0],h[1]) else nearest({t->e.center+e.axis*(e.a*cos(2*PI*t))+e.normal*(e.b*sin(2*PI*t))},p).first}
            else->error("复杂尺规类型无效")
        }
    }
    data class Guide(val points:List<AssistantPoint>,val infinite:Boolean=false)
    fun guides(a:JSONObject):List<Guide> {
        val h=ArtAssistants.points(a);val type=a.getString("type");val rays=a.optInt("rays",16)
        fun sampled(f:(Double)->AssistantPoint)=Guide((0..128).map {f(it/128.0)})
        return when(type) {
            "spline"->listOf(sampled {spline(h,it)})
            "perspective_grid"->{val q=Quad(h);val divisions=maxOf(1,a.optInt("subdivisions",10))
                buildList {for(i in 0..divisions) {val t=i.toDouble()/divisions
                    add(Guide(listOf(q.at(t,0.0),q.at(t,1.0))));add(Guide(listOf(q.at(0.0,t),q.at(1.0,t))))}}}
            "perspective_ellipse"->{val q=Quad(h);listOf(Guide(h+h.first()),sampled {q.at(0.5+0.5*cos(2*PI*it),0.5+0.5*sin(2*PI*it))})}
            "two_vanishing_points"->{val axis=(h[1]-h[0])*(1/(h[1]-h[0]).length());val n=AssistantPoint(-axis.y,axis.x)
                buildList {add(Guide(listOf(h[0],h[1]),true))
                    for(i in -rays/2..rays/2) {val anchor=h[2]+n*((h[1]-h[0]).length()*i/rays)
                        for(v in h.take(2))if((anchor-v).length()>=0.01)add(Guide(listOf(v,anchor),true))}
                    if(a.optBoolean("useVertical",true))add(Guide(listOf(h[2],h[2]+n),true))}}
            "fisheye"->{val e=ArtAssistants.ellipse(a);buildList {add(Guide(listOf(h[0],h[1]),true))
                for(i in 1..minOf(rays,16)) {val b=e.b*i/4.0;add(sampled {t->e.center+e.axis*(e.a*cos(2*PI*t))+e.normal*(b*sin(2*PI*t))})}}}
            "curvilinear_perspective"->{val axis=h[1]-h[0];val n=AssistantPoint(-axis.y,axis.x);val mid=(h[0]+h[1])*0.5
                buildList {add(Guide(h,true))
                    for(i in 1..minOf(rays,16))for(sign in listOf(-1,1)) {
                        val c=requireNotNull(circle(h[0],h[1],mid+n*(sign*i/(minOf(rays,16)+1.0))))
                        add(sampled {t->c.center+AssistantPoint(cos(2*PI*t),sin(2*PI*t))*c.radius})}}}
            else->emptyList()
        }
    }
}
