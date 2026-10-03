package com.ai.limbs.plugins.artstudio

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Independent raster deformation math. Every non-affine mode supplies an actual spatial mapping. */
internal object ArtTransform {
    data class V(val x:Double,val y:Double) {
        operator fun plus(v:V)=V(x+v.x,y+v.y)
        operator fun minus(v:V)=V(x-v.x,y-v.y)
        operator fun times(s:Double)=V(x*s,y*s)
        fun cross(v:V)=x*v.y-y*v.x
    }
    data class Triangle(val source:List<V>,val target:List<V>)
    data class Plan(val bounds:Rect,val inverse:Matrix?,val triangles:List<Triangle>,val identity:Boolean)
    val modes=listOf("affine","perspective","distort","warp","cage","liquify","mesh")
    val filters=listOf("nearest","bilinear","bicubic")
    fun info()=JSONObject().put("examples",examples()).put("modes",JSONArray(modes)).put("interpolation",JSONArray(filters))
        .put("coordinates","document pixels; affine scale/shear about pivot, positive Y downward")
        .put("layerAffine","Non-destructive affine on any editable layer/group; existing move/scale/rotate retained")
        .put("exampleCapability","plugin.art.studio.transform.apply").put("pixelScope","selection: paint/image only, cut soft coverage; layer: explicit bake=true plus sourceBounds, flatten selected layer/group to 8-bit paint; pixels outside the declared source box are discarded")
        .put("warp","Affine moving least squares; 3..32 non-collinear control pairs, gridResolution=16 (2..64)")
        .put("cage","Convex 3..32-vertex enclosing cage, mean value coordinates; convex target; gridResolution=16")
        .put("mesh","2..9 rows/columns, row-major target nodes; bilinear cells tessellated subdivisions=4 (1..8), no Bezier tangent handles")
        .put("liquify","Up to 128 ordered push/expand/contract/twirl dabs; finite radius, strength and smooth radial falloff; gridResolution=32; radius must cover a source grid cell diagonal")
        .put("limits","Each source/output <=4194304 pixels, edge<=16384; grid<=8192 triangles; folded grids, self-intersections and projective horizons are rejected")
        .put("preview","Geometry preview is read-only; phone has editable control points and explicit Apply/Cancel; no full-resolution live image preview")
    private fun examples():JSONObject {
        val common=JSONObject().put("documentId","<documentId>").put("expectedRevision",0).put("layerId","<layerId>")
            .put("scope","layer").put("bake",true).put("sourceBounds",JSONObject().put("x",0).put("y",0).put("width",200).put("height",100)).put("interpolation","bilinear")
        val corners=JSONArray(listOf(listOf(0,0),listOf(200,0),listOf(200,100),listOf(0,100)))
        val result=JSONObject()
        for(mode in modes) {
            val p=JSONObject(common.toString()).put("mode",mode)
            when(mode) {
                "affine"->p.put("scaleX",1.2).put("scaleY",0.8).put("shearX",0.2).put("pivotX",100).put("pivotY",50)
                "perspective","distort"->p.put("points",JSONArray(listOf(listOf(20,0),listOf(180,10),listOf(200,100),listOf(0,100))))
                "warp"->p.put("sourcePoints",JSONArray(corners.toString()).put(JSONArray(listOf(100,50)))).put("points",JSONArray(corners.toString()).put(JSONArray(listOf(110,55))))
                "cage"->p.put("sourcePoints",JSONArray(corners.toString())).put("points",JSONArray(listOf(listOf(0,0),listOf(210,0),listOf(190,110),listOf(10,100))))
                "mesh"->p.put("columns",3).put("rows",3).put("points",JSONArray(listOf(listOf(0,0),listOf(100,0),listOf(200,0),listOf(0,50),listOf(110,55),listOf(200,50),listOf(0,100),listOf(100,100),listOf(200,100))))
                "liquify"->p.put("dabs",JSONArray().put(JSONObject().put("kind","push").put("x",100).put("y",50).put("radius",40).put("strength",0.2).put("dx",10).put("dy",0)))
            };result.put(mode,p)
        };return result
    }
    private fun number(p:JSONObject,k:String,default:Double,limit:Double=1000000.0):Double {
        val v=if(p.has(k))p.getDouble(k) else default
        require(v.isFinite() && abs(v)<=limit) {"${k}参数无效"};return v
    }
    private fun integer(p:JSONObject,key:String,default:Int,min:Int,max:Int):Int {
        val value=if(p.has(key))p.get(key) else default
        require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && value.toDouble() in min.toDouble()..max.toDouble()) {"${key}须为${min}..${max}整数"}
        return value.toInt()
    }
    fun affine(p:JSONObject):Matrix {
        val sx=number(p,"scaleX",1.0,100.0);val sy=number(p,"scaleY",1.0,100.0)
        require(abs(sx)>=0.01 && abs(sy)>=0.01)
        val hx=number(p,"shearX",0.0,10.0);val hy=number(p,"shearY",0.0,10.0)
        require(abs(1-hx*hy)>=0.0001) {"剪切矩阵不可逆"}
        val angle=Math.toRadians(number(p,"rotation",0.0,36000.0));val c=cos(angle);val s=sin(angle)
        val a=sx*(c-s*hy);val b=sx*(s+c*hy);val d=sy*(c+s*hx);val cc=sy*(c*hx-s)
        val px=number(p,"pivotX",0.0);val py=number(p,"pivotY",0.0)
        return ArtShapes.matrix(JSONArray(listOf(a,b,cc,d,px-a*px-cc*py+number(p,"dx",0.0),py-b*px-d*py+number(p,"dy",0.0))))
    }
    private fun points(a:JSONArray,min:Int,max:Int):List<V> {
        require(a.length() in min..max)
        return (0 until a.length()).map {i->val q=a.getJSONArray(i);require(q.length()==2)
            V(q.getDouble(0),q.getDouble(1)).also {require(it.x.isFinite()&&it.y.isFinite()&&abs(it.x)<=1000000&&abs(it.y)<=1000000)}}
    }
    private fun corners(r:Rect)=listOf(V(r.left.toDouble(),r.top.toDouble()),V(r.right.toDouble(),r.top.toDouble()),V(r.right.toDouble(),r.bottom.toDouble()),V(r.left.toDouble(),r.bottom.toDouble()))
    private fun convex(v:List<V>):Double {
        var orientation=0.0
        for(i in v.indices) {
            val z=(v[(i+1)%v.size]-v[i]).cross(v[(i+2)%v.size]-v[(i+1)%v.size])
            require(abs(z)>1e-7) {"控制多边形须严格凸且无重合点"}
            if(i==0)orientation=sign(z) else require(sign(z)==orientation) {"控制多边形须严格凸"}
        }
        simple(v);return orientation
    }
    private fun simple(v:List<V>) {
        for(i in v.indices)for(j in i+1 until v.size) {
            if(j==i+1 || (i==0 && j==v.lastIndex))continue
            val a=v[i];val b=v[(i+1)%v.size];val c=v[j];val d=v[(j+1)%v.size]
            val u=(b-a).cross(c-a);val w=(b-a).cross(d-a);val t=(d-c).cross(a-c);val z=(d-c).cross(b-c)
            require(!(u*w<=0 && t*z<=0 && max(a.x,b.x)>=min(c.x,d.x) && max(c.x,d.x)>=min(a.x,b.x) && max(a.y,b.y)>=min(c.y,d.y) && max(c.y,d.y)>=min(a.y,b.y))) {"变换边界发生交叉"}
        }
    }
    private fun map(m:Matrix,v:V):V {val q=floatArrayOf(v.x.toFloat(),v.y.toFloat());m.mapPoints(q);return V(q[0].toDouble(),q[1].toDouble())}
    private fun bounds(v:List<V>):Rect {
        require(v.all {it.x.isFinite()&&it.y.isFinite()&&abs(it.x)<=1000000&&abs(it.y)<=1000000})
        val r=Rect(floor(v.minOf {it.x}).toInt(),floor(v.minOf {it.y}).toInt(),ceil(v.maxOf {it.x}).toInt(),ceil(v.maxOf {it.y}).toInt())
        require(!r.isEmpty && r.width() in 1..16384 && r.height() in 1..16384 && r.width().toLong()*r.height()<=ArtRasterSelection.MAX_PIXELS) {"变换输出范围超限"};return r
    }
    internal fun mvc(v:V,p:List<V>,q:List<V>):V {
        val diff=p.map {it-v};val dist=diff.map {hypot(it.x,it.y)}
        val exact=dist.indexOfFirst {it<1e-8};if(exact>=0)return q[exact]
        for(i in p.indices) {
            val j=(i+1)%p.size;val edge=p[j]-p[i];val t=((v-p[i]).x*edge.x+(v-p[i]).y*edge.y)/(edge.x*edge.x+edge.y*edge.y)
            if(t in 0.0..1.0 && abs(edge.cross(v-p[i]))<1e-7)return q[i]*(1-t)+q[j]*t
        }
        val half=p.indices.map {i->val j=(i+1)%p.size;diff[i].cross(diff[j])/(dist[i]*dist[j]+diff[i].x*diff[j].x+diff[i].y*diff[j].y)}
        val weights=p.indices.map {i->(half[(i+p.size-1)%p.size]+half[i])/dist[i]};val sum=weights.sum()
        require(sum.isFinite()&&abs(sum)>1e-10)
        return p.indices.fold(V(0.0,0.0)) {r,i->r+q[i]*(weights[i]/sum)}
    }
    internal fun mls(v:V,p:List<V>,q:List<V>,alpha:Double):V {
        val ds=p.map {val d=it-v;d.x*d.x+d.y*d.y};val exact=ds.indexOfFirst {it<1e-10};if(exact>=0)return q[exact]
        val w=ds.map {1.0/it.pow(alpha)};val sum=w.sum()
        val pc=p.indices.fold(V(0.0,0.0)) {r,i->r+p[i]*(w[i]/sum)}
        val qc=q.indices.fold(V(0.0,0.0)) {r,i->r+q[i]*(w[i]/sum)}
        var xx=0.0;var xy=0.0;var yy=0.0;var ax=0.0;var ay=0.0;var bx=0.0;var by=0.0
        for(i in p.indices) {val a=p[i]-pc;val b=q[i]-qc;val k=w[i]/sum
            xx+=k*a.x*a.x;xy+=k*a.x*a.y;yy+=k*a.y*a.y;ax+=k*b.x*a.x;ay+=k*b.x*a.y;bx+=k*b.y*a.x;by+=k*b.y*a.y}
        val det=xx*yy-xy*xy;require(det>1e-12) {"扭曲控制点不能共线"}
        val d=v-pc;val u=(yy*d.x-xy*d.y)/det;val t=(xx*d.y-xy*d.x)/det
        return qc+V(ax*u+ay*t,bx*u+by*t)
    }
    internal fun dab(v:V,p:JSONObject):V {
        val base=V(number(p,"x",0.0),number(p,"y",0.0));val radius=number(p,"radius",64.0,16384.0);require(radius>=1)
        val amount=number(p,"strength",0.2,1.0);require(amount>=0)
        require(p.getString("kind") in setOf("push","expand","contract","twirl"))
        if(p.getString("kind")=="push")require(hypot(number(p,"dx",0.0,radius),number(p,"dy",0.0,radius))<=radius)
        if(p.getString("kind")=="twirl")number(p,"angle",30.0,180.0)
        val d=v-base;val distance=hypot(d.x,d.y);if(distance>=radius)return v
        val fall=(1-distance/radius).let {it*it*(3-2*it)}*amount
        return when(p.getString("kind")) {
            "push" -> {val dx=number(p,"dx",0.0,radius);val dy=number(p,"dy",0.0,radius);require(hypot(dx,dy)<=radius);v+V(dx,dy)*fall}
            "expand" -> base+d*(1+fall)
            "contract" -> base+d*(1-fall*0.8)
            "twirl" -> {val a=Math.toRadians(number(p,"angle",30.0,180.0))*fall;base+V(cos(a)*d.x-sin(a)*d.y,sin(a)*d.x+cos(a)*d.y)}
            else -> error("液化kind须为push/expand/contract/twirl")
        }
    }
    fun plan(r:Rect,p:JSONObject):Plan {
        val mode=p.getString("mode");require(mode in modes);require(p.optString("interpolation","bilinear") in filters)
        val src=corners(r)
        if(mode in setOf("affine","perspective")) {
            val m=if(mode=="affine")affine(p) else {
                val dst=points(p.getJSONArray("points"),4,4);require(convex(dst)>0)
                Matrix().apply {require(setPolyToPoly(src.flatMap {listOf(it.x.toFloat(),it.y.toFloat())}.toFloatArray(),0,dst.flatMap {listOf(it.x.toFloat(),it.y.toFloat())}.toFloatArray(),0,4)) {"透视控制点不可逆"}}
            }
            val values=FloatArray(9);m.getValues(values)
            val denominators=src.map {values[6]*it.x+values[7]*it.y+values[8]}
            require(denominators.all {it>1e-8}||denominators.all {it< -1e-8}) {"透视穿过无穷远平面"}
            val dst=src.map {map(m,it)};val inverse=Matrix();require(m.invert(inverse))
            return Plan(bounds(dst),inverse,emptyList(),src.indices.all {hypot(dst[it].x-src[it].x,dst[it].y-src[it].y)<1e-6})
        }
        val resolution=integer(p,"gridResolution",if(mode=="liquify")32 else 16,2,64)
        var columns=resolution+1;var rows=resolution+1
        val forward:(V)->V=when(mode) {
            "distort" -> {val q=points(p.getJSONArray("points"),4,4);require(convex(q)>0)
                val fn:(V)->V={v:V->val u=(v.x-r.left)/r.width();val t=(v.y-r.top)/r.height();q[0]*((1-u)*(1-t))+q[1]*(u*(1-t))+q[2]*(u*t)+q[3]*((1-u)*t)};fn}
            "warp" -> {val a=points(p.getJSONArray("sourcePoints"),3,32);val b=points(p.getJSONArray("points"),a.size,a.size)
                val alpha=number(p,"alpha",1.0,4.0);require(alpha>=0.25);mls(src[0],a,b,alpha)
                val fn:(V)->V={v:V->mls(v,a,b,alpha)};fn}
            "cage" -> {val a=points(p.getJSONArray("sourcePoints"),3,32);val b=points(p.getJSONArray("points"),a.size,a.size)
                require(convex(a)==convex(b));require(src.all {v->a.indices.all {i->(a[(i+1)%a.size]-a[i]).cross(v-a[i])*convex(a)>= -1e-7}}) {"原笼形须包围整个源框"}
                val fn:(V)->V={v:V->mvc(v,a,b)};fn}
            "liquify" -> {val a=p.getJSONArray("dabs");require(a.length() in 1..128)
                val dabs=(0 until a.length()).map {a.getJSONObject(it)}
                val minimumRadius=hypot(r.width().toDouble()/resolution,r.height().toDouble()/resolution)
                dabs.forEach {dab(src[0],it);require(number(it,"radius",64.0,16384.0)>=minimumRadius) {"液化半径小于网格单元对角线，请增加gridResolution或缩小源框"}}
                val fn:(V)->V={v:V->dabs.fold(v) {point,d->dab(point,d)}};fn}
            "mesh" -> {
                require(p.has("columns")&&p.has("rows"));val cols=integer(p,"columns",0,2,9);val rs=integer(p,"rows",0,2,9)
                val q=points(p.getJSONArray("points"),cols*rs,cols*rs);val subdivisions=integer(p,"subdivisions",4,1,8)
                columns=(cols-1)*subdivisions+1;rows=(rs-1)*subdivisions+1
                val fn:(V)->V={v:V->val xx=((v.x-r.left)/r.width()*(cols-1)).coerceIn(0.0,(cols-1).toDouble());val yy=((v.y-r.top)/r.height()*(rs-1)).coerceIn(0.0,(rs-1).toDouble())
                    val i=floor(xx).toInt().coerceAtMost(cols-2);val j=floor(yy).toInt().coerceAtMost(rs-2);val u=xx-i;val t=yy-j
                    q[j*cols+i]*((1-u)*(1-t))+q[j*cols+i+1]*(u*(1-t))+q[(j+1)*cols+i+1]*(u*t)+q[(j+1)*cols+i]*((1-u)*t)};fn
            }
            else -> error("未知变形模式")
        }
        require((columns-1)*(rows-1)*2<=8192)
        val source=(0 until rows).flatMap {y->(0 until columns).map {x->V(r.left+r.width()*x.toDouble()/(columns-1),r.top+r.height()*y.toDouble()/(rows-1))}}
        val target=source.map(forward)
        val border=(0 until columns).map {target[it]}+(1 until rows).map {target[it*columns+columns-1]}+(columns-2 downTo 0).map {target[(rows-1)*columns+it]}+(rows-2 downTo 1).map {target[it*columns]}
        simple(border)
        val triangles=mutableListOf<Triangle>()
        for(y in 0 until rows-1)for(x in 0 until columns-1) {
            val a=y*columns+x;val b=a+1;val c=a+columns+1;val d=a+columns
            for(ids in listOf(listOf(a,b,c),listOf(a,c,d))) {
                val dst=ids.map {target[it]};require((dst[1]-dst[0]).cross(dst[2]-dst[0])>1e-7) {"控制网格折叠，请减小变形强度或调整控制点"}
                triangles.add(Triangle(ids.map {source[it]},dst))
            }
        }
        return Plan(bounds(target),null,triangles,source.indices.all {hypot(source[it].x-target[it].x,source[it].y-target[it].y)<1e-6})
    }
    fun geometry(r:Rect,p:JSONObject):JSONObject {
        val plan=plan(r,p);val b=plan.bounds
        return JSONObject().put("outputBounds",JSONObject().put("x",b.left).put("y",b.top).put("width",b.width()).put("height",b.height()))
            .put("identity",plan.identity).put("triangles",plan.triangles.size).put("mode",p.getString("mode"))
    }
}
