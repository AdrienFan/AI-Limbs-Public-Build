package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** A multibrush stroke stores its transforms; replay never reads the current toolbar or canvas size. */
internal object ArtMirror {
    val directions=linkedMapOf("vertical" to "左右镜像","horizontal" to "上下镜像","quad" to "四象限镜像",
        "radial" to "旋转对称","snowflake" to "雪花对称","translate" to "随机平移",
        "copytranslate" to "自定子画笔","interval" to "间隔复制")
    const val MAX_COPIES=48
    // [a,b,c,d,tx,ty]: x'=a*x+c*y+tx, y'=b*x+d*y+ty.
    data class Transform(val a:Double=1.0,val b:Double=0.0,val c:Double=0.0,val d:Double=1.0,val tx:Double=0.0,val ty:Double=0.0) {
        fun json()=JSONArray().put(a).put(b).put(c).put(d).put(tx).put(ty)
        fun then(right:Transform)=Transform(a*right.a+c*right.b,b*right.a+d*right.b,
            a*right.c+c*right.d,b*right.c+d*right.d,a*right.tx+c*right.ty+tx,b*right.tx+d*right.ty+ty)
    }
    private fun rotate(degrees:Double):Transform {
        val r=degrees*PI/180;return Transform(cos(r),sin(r),-sin(r),cos(r))
    }
    private fun around(t:Transform,x:Double,y:Double)=t.copy(tx=x-t.a*x-t.c*y,ty=y-t.b*x-t.d*y)
    private fun number(p:JSONObject,key:String,default:Double,range:ClosedFloatingPointRange<Double>):Double {
        val value=if(p.has(key))p.getDouble(key) else default
        require(value.isFinite() && value in range) {key+" 超出范围"};return value
    }
    fun normalize(stroke:JSONObject,canvasWidth:Int,canvasHeight:Int):JSONObject {
        require(stroke.getString("tool")=="mirror")
        val p=JSONObject(stroke.toString());val direction=p.optString("mirrorDirection","vertical");require(direction in directions)
        val count=number(p,"mirrorCount",6.0,2.0..12.0);require(count==floor(count))
        val x=number(p,"axisX",canvasWidth/2.0,0.0..canvasWidth.toDouble())
        val y=number(p,"axisY",canvasHeight/2.0,0.0..canvasHeight.toDouble())
        val angle=number(p,"mirrorAngle",0.0,-360.0..360.0)
        val radius=number(p,"mirrorRadius",80.0,0.0..512.0)
        val seed=if(p.has("mirrorSeed"))p.getInt("mirrorSeed") else java.util.Random().nextInt(Int.MAX_VALUE);require(seed>=0)
        val centers=if(p.has("mirrorCenters"))p.getJSONArray("mirrorCenters") else JSONArray();require(centers.length()<=11)
        for(i in 0 until centers.length()) {
            val point=centers.getJSONArray(i);require(point.length()==2)
            require(point.getDouble(0).isFinite() && point.getDouble(0) in 0.0..canvasWidth.toDouble() &&
                point.getDouble(1).isFinite() && point.getDouble(1) in 0.0..canvasHeight.toDouble()) {"子画笔中心须在画布范围内"}
        }
        val ix=number(p,"mirrorIntervalX",1024.0,128.0..2048.0);val iy=number(p,"mirrorIntervalY",1024.0,128.0..2048.0)
        require(ix==floor(ix) && iy==floor(iy))
        val brushTool=p.optString("brushTool","ink");require(brushTool in ArtBrush.tools) {"请选择已实现的栅格笔刷"}
        val transforms=mutableListOf<Transform>()
        val orientation=rotate(-angle);val inverse=rotate(angle)
        fun copy(t:Transform) {require(transforms.size<MAX_COPIES);transforms.add(t)}
        fun axis(t:Transform)=around(orientation.then(t).then(inverse),x,y)
        if(direction !in setOf("translate","interval"))copy(Transform())
        when(direction) {
            "vertical"->copy(axis(Transform(a=-1.0)))
            "horizontal"->copy(axis(Transform(d=-1.0)))
            "quad"->{copy(axis(Transform(a=-1.0)));copy(axis(Transform(d=-1.0)));copy(axis(Transform(a=-1.0,d=-1.0)))}
            "radial","snowflake"->{
                val arms=if(direction=="snowflake")count.toInt()*2 else count.toInt()
                for(arm in 1 until arms)copy(around(rotate(360.0*arm/arms),x,y))
                if(direction=="snowflake")for(arm in 0 until arms) {
                    val wedge=180.0/arms
                    copy(axis(rotate((2*arm-1)*wedge).then(Transform(a=-1.0)).then(rotate(wedge))))
                }
            }
            "translate"->{
                val random=java.util.Random(seed.toLong())
                repeat(count.toInt()) {
                    val theta=random.nextDouble()*2*PI;val distance=random.nextDouble()*radius
                    val dx=cos(theta)*distance;val dy=sin(theta)*distance
                    copy(Transform(tx=orientation.a*dx+orientation.c*dy,ty=orientation.b*dx+orientation.d*dy))
                }
            }
            "copytranslate"->for(i in 0 until centers.length()) {
                val point=centers.getJSONArray(i);copy(Transform(tx=point.getDouble(0)-x,ty=point.getDouble(1)-y))
            }
            "interval"->{
                val nx=canvasWidth/ix.toInt()+1;val ny=canvasHeight/iy.toInt()+1
                require(nx.toLong()*ny<=MAX_COPIES) {"间隔复制最多48支，请增大间隔"}
                val origin=p.getJSONArray("points").getJSONArray(0)
                val anchorX=floor(origin.getDouble(0)/ix)*ix;val anchorY=floor(origin.getDouble(1)/iy)*iy
                for(col in 0 until nx)for(row in 0 until ny)copy(Transform(tx=col*ix-anchorX,ty=row*iy-anchorY))
            }
        }
        return p.put("brushTool",brushTool).put("mirrorDirection",direction).put("mirrorCount",count.toInt())
            .put("mirrorAngle",angle).put("axisX",x).put("axisY",y).put("mirrorRadius",radius).put("mirrorSeed",seed)
            .put("mirrorCenters",JSONArray(centers.toString())).put("mirrorIntervalX",ix.toInt()).put("mirrorIntervalY",iy.toInt())
            .put("canvasWidth",canvasWidth).put("canvasHeight",canvasHeight).put("mirrorVersion",1)
            .put("mirrorTransforms",JSONArray(transforms.map {it.json()}))
    }
    fun validateStored(stroke:JSONObject) {
        require(stroke.getInt("mirrorVersion")==1 && stroke.getString("brushTool") in ArtBrush.tools)
        require(stroke.getString("mirrorDirection") in directions)
        val angle=stroke.getDouble("mirrorAngle");require(angle.isFinite() && angle in -360.0..360.0)
        val copies=stroke.getJSONArray("mirrorTransforms");require(copies.length() in 1..MAX_COPIES)
        for(i in 0 until copies.length()) {
            val t=copies.getJSONArray(i);require(t.length()==6)
            for(j in 0..5)require(t.getDouble(j).isFinite())
            val a=t.getDouble(0);val b=t.getDouble(1);val c=t.getDouble(2);val d=t.getDouble(3)
            require(abs(a*a+b*b-1)<0.00001 && abs(c*c+d*d-1)<0.00001 && abs(a*c+b*d)<0.00001)
            require(abs(t.getDouble(4))<=2_000_000 && abs(t.getDouble(5))<=2_000_000)
        }
    }
    fun requireBudget(stroke:JSONObject) {
        val copies=stroke.getJSONArray("mirrorTransforms").length();val count=stroke.getJSONObject("brush").getInt("count");var n=0L
        ArtBrush.dabs(stroke) {
            n++;require(n*copies<=ArtBrush.MAX_DABS && n*copies*count<=ArtBrush.MAX_PARTICLES) {
                "多重画笔总印章/粒子预算超限，请增大间距、减少副笔或分段绘制"
            }
        }
    }
    fun info()=JSONObject().put("directions",JSONObject(directions)).put("brushTools",JSONObject(ArtBrush.tools))
        .put("defaults",JSONObject().put("mirrorDirection","vertical").put("brushTool","ink").put("mirrorAngle",0)
            .put("mirrorCount",6).put("mirrorRadius",80).put("mirrorCenters",JSONArray()).put("mirrorIntervalX",1024).put("mirrorIntervalY",1024))
        .put("angleRange",JSONArray().put(-360).put(360)).put("angleUnit","度；正数为图层局部坐标中逆时针")
        .put("angleEffect","左右/上下/四象限/雪花旋转对称轴；随机平移旋转偏移；旋转对称仅改变轴线显示，副笔相对转角不变；自定子画笔/间隔复制不受角度影响")
        .put("coordinateSpace","points、axisX/Y及mirrorCenters均为图层局部像素；assistant.stroke输入点为文档像素")
        .put("pipeline","尺规吸附主轨迹 → 共享平滑/稳定器 → 一组固定变换生成副笔；完整brush参数见brush.info(tool=brushTool)")
        .put("limits",JSONObject().put("copies",MAX_COPIES).put("totalDabs",ArtBrush.MAX_DABS).put("totalParticles",ArtBrush.MAX_PARTICLES))
}
