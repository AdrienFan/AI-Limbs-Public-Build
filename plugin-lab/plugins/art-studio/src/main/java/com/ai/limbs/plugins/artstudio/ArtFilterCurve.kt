package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import kotlin.math.*

/** Bounded configuration with linear or shape-preserving Hermite interpolation. */
internal class ArtFilterCurve(input:JSONArray, private val interpolation:String="smooth",
    private val outside:String="linear") {
    private val x:DoubleArray
    private val y:DoubleArray
    private val tangent:DoubleArray
    init {
        require(interpolation in setOf("linear","smooth") && outside in setOf("linear","constant"))
        require(input.length() in 2..32) {"曲线需要2–32个节点"}
        x=DoubleArray(input.length());y=DoubleArray(input.length())
        for(i in x.indices) {
            val point=input.getJSONArray(i);require(point.length()==2)
            val px=point.get(0);val py=point.get(1)
            require(px is Number && py is Number)
            x[i]=px.toDouble();y[i]=py.toDouble()
            require(x[i].isFinite() && x[i] in 0.0..1.0 && y[i].isFinite() && y[i] in -16.0..16.0)
            if(i>0)require(x[i]>x[i-1]) {"曲线节点x必须严格递增"}
        }
        require(x.first()==0.0 && x.last()==1.0) {"曲线必须包含x=0和x=1端点"}
        val h=DoubleArray(x.size-1) {x[it+1]-x[it]}
        val d=DoubleArray(h.size) {(y[it+1]-y[it])/h[it]}
        require(d.all {it.isFinite()}) {"节点过近，曲线斜率超出有限范围"}
        tangent=DoubleArray(x.size)
        tangent[0]=d.first();tangent[tangent.lastIndex]=d.last()
        for(i in 1 until x.lastIndex) {
            if(d[i-1]*d[i]<=0)tangent[i]=0.0 else {
                val a=2*h[i]+h[i-1];val b=h[i]+2*h[i-1]
                tangent[i]=(a+b)/(a/d[i-1]+b/d[i])
            }
        }
        require(tangent.all {it.isFinite()}) {"曲线切线超出有限范围"}
    }
    fun constant(value:Double)=y.all {it==value}
    fun at(value:Double):Double {
        require(value.isFinite())
        if(value<0)return if(outside=="constant")y.first() else y.first()+value*(y[1]-y[0])/(x[1]-x[0])
        if(value>1)return if(outside=="constant")y.last() else y.last()+(value-1)*(y.last()-y[y.lastIndex-1])/(x.last()-x[x.lastIndex-1])
        var i=0;while(i<x.size-2 && value>x[i+1])i++
        val h=x[i+1]-x[i];val t=(value-x[i])/h
        if(interpolation=="linear")return y[i]*(1-t)+y[i+1]*t
        val t2=t*t;val t3=t2*t
        return (2*t3-3*t2+1)*y[i]+(t3-2*t2+t)*h*tangent[i]+(-2*t3+3*t2)*y[i+1]+(t3-t2)*h*tangent[i+1]
    }
}
