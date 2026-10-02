package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Geometry is resolved in document space; sensor samples and immutable endpoints stay layer-local. */
internal object ArtLine {
    val guideTypes=setOf("ruler","infinite_ruler","parallel_ruler","vanishing_point")
    fun settings(p:JSONObject):JSONObject {
        val tool=p.optString("brushTool","ink");require(tool in ArtBrush.tools)
        val step=p.optDouble("angleStep",0.0)
        require(step.isFinite() && (step==0.0 || step in 1.0..180.0)) {"角度步长须为0（自由）或1–180°"}
        val offset=p.optJSONArray("lineOffset") ?: JSONArray().put(0).put(0)
        require(offset.length()==2 && (0..1).all {offset.getDouble(it).isFinite() && abs(offset.getDouble(it))<=1_000_000})
        require(step==0.0 || !p.has("assistantId")) {"角度约束与尺规吸附不能同时指定"}
        return JSONObject(p.toString()).put("brushTool",tool).put("useSensors",p.optBoolean("useSensors",true))
            .put("angleStep",step).put("lineOffset",JSONArray(offset.toString()))
    }
    fun geometry(p:JSONObject,state:JSONObject):JSONObject {
        val out=settings(p);val input=ArtBrush.samples(p.getJSONArray("points"));require(input.size>=2) {"直线至少需要起点和终点"}
        val layer=ArtMenuOperations.layers(state).first {it.getString("id")==p.getString("layerId")}
        val matrix=ArtShapes.layerMatrix(state,layer);val inverse=Matrix();require(matrix.invert(inverse))
        fun mapped(s:ArtBrush.Sample,m:Matrix):ArtBrush.Sample {
            val v=floatArrayOf(s.x.toFloat(),s.y.toFloat());m.mapPoints(v);return s.copy(x=v[0].toDouble(),y=v[1].toDouble())
        }
        val original=input.map {mapped(it,matrix)};val a=original.first();val b=original.last()
        val sourceLength=hypot(b.x-a.x,b.y-a.y);require(sourceLength>0.00001) {"直线起终点不能重合"}
        val offset=out.getJSONArray("lineOffset")
        var start=AssistantPoint(a.x+offset.getDouble(0),a.y+offset.getDouble(1))
        var end=AssistantPoint(b.x+offset.getDouble(0),b.y+offset.getDouble(1))
        val step=out.getDouble("angleStep")
        if(step>0) {
            val degrees=(atan2(end.y-start.y,end.x-start.x)*180/PI+360)%360
            val angle=floor(degrees/step+0.5)*step*PI/180
            end=start+AssistantPoint(cos(angle),sin(angle))*sourceLength
        }
        if(out.has("assistantId")) {
            val guide=ArtAssistants.items(state).firstOrNull {it.getString("id")==out.getString("assistantId")} ?: error("尺规不存在")
            require(guide.getBoolean("visible") && guide.getBoolean("enabled") && guide.getString("type") in guideTypes) {"直线只吸附启用且可见的直线尺规"}
            val projection=ArtAssistants.Projection(guide,start);end=projection.project(end);start=projection.project(start)
        }
        val length=(end-start).length();require(length>0.00001) {"吸附后的直线起终点重合"}
        // Radial distance carries each captured sensor sample to the new straight axis. Shortening removes samples beyond the endpoint.
        val points=mutableListOf<ArtBrush.Sample>()
        fun place(s:ArtBrush.Sample,t:Double) {
            val position=start+(end-start)*t
            val sample=if(out.getBoolean("useSensors"))s else s.copy(pressure=1.0,tilt=0.0,rotation=0.0)
            points.add(mapped(sample.copy(x=position.x,y=position.y),inverse))
        }
        place(a,0.0)
        var lastDistance=0.0
        original.drop(1).dropLast(1).forEach {s ->
            val distance=hypot(s.x-a.x,s.y-a.y)
            if(distance>lastDistance && distance<sourceLength) {place(s,distance/sourceLength);lastDistance=distance}
        }
        place(b,1.0)
        val final=JSONArray(points.map {it.json()});ArtBrush.samples(final)
        return out.put("tool","line").put("lineVersion",1).put("lineInput",JSONArray(p.getJSONArray("points").toString()))
            .put("lineEndpoints",JSONArray().put(points.first().json()).put(points.last().json())).put("points",final)
    }
    fun validateStored(p:JSONObject) {
        require(p.getInt("lineVersion")==1);settings(p)
        require(ArtBrush.samples(p.getJSONArray("lineInput")).size>=2)
        val endpoints=ArtBrush.samples(p.getJSONArray("lineEndpoints"));require(endpoints.size==2)
        require(hypot(endpoints[1].x-endpoints[0].x,endpoints[1].y-endpoints[0].y)>0.00001)
    }
    fun info()=JSONObject().put("brushTools",JSONObject(ArtBrush.tools)).put("guideTypes",JSONArray(guideTypes.toList()))
        .put("defaults",JSONObject().put("brushTool","ink").put("useSensors",true).put("angleStep",0).put("lineOffset",JSONArray().put(0).put(0)))
        .put("coordinates","points/width: layer-local pixels; angleStep/lineOffset/guides: document space")
        .put("sensors","points=[x,y,pressure,timeMs,tilt,rotation]; 2–10000 samples; brush.info lists curves and ranges")
        .put("paint","Current dab-v1 brush; straight geometry disables weighted/stabilizer and timed airbrush; pixel_perfect remains available")
        .put("vector","Editable two-endpoint line with color/width/opacity; raster tips/textures/sensors do not style vector lines")
        .put("interaction","Drag/release; Shift=15 degrees; Alt after starting translates the draft; mobile holdDraft + moveStart, then finish/cancel")
}
