package com.ai.limbs.plugins.artstudio

import kotlin.math.*

/** Plugin working model: linear Rec.709 / extended sRGB, with a D65 CIELab option. Not an ICC engine. */
internal object ArtFilterColorSpace {
    fun encode(v:Double)=if(abs(v)<=.0031308)12.92*v else sign(v)*(1.055*abs(v).pow(1/2.4)-.055)
    fun decode(v:Double)=if(abs(v)<=.04045)v/12.92 else sign(v)*((abs(v)+.055)/1.055).pow(2.4)
    fun luminance(c:DoubleArray)=.2126*c[0]+.7152*c[1]+.0722*c[2]
    fun lab(c:DoubleArray):DoubleArray {
        fun f(v:Double)=if(v>216.0/24389)cbrt(v) else (24389.0/27*v+16)/116
        val x=f((.4124564*c[0]+.3575761*c[1]+.1804375*c[2])/.95047)
        val y=f(.2126729*c[0]+.7151522*c[1]+.072175*c[2])
        val z=f((.0193339*c[0]+.119192*c[1]+.9503041*c[2])/1.08883)
        return doubleArrayOf(116*y-16,500*(x-y),200*(y-z))
    }
    fun fromLab(c:DoubleArray):DoubleArray {
        fun f(v:Double)=if(v>6.0/29)v*v*v else 3*(6.0/29).pow(2)*(v-4.0/29)
        val y=(c[0]+16)/116;val x=f(y+c[1]/500)*.95047;val yy=f(y);val z=f(y-c[2]/200)*1.08883
        return doubleArrayOf(3.2404542*x-1.5371385*yy-.4985314*z,-.969266*x+1.8760108*yy+.041556*z,
            .0556434*x-.2040259*yy+1.0572252*z)
    }
    /** HSV/HSL are explicitly bounded appearance operations. Hue is normalized to one turn. */
    fun hue(c:DoubleArray):Double {
        val max=c.maxOrNull()!!;val min=c.minOrNull()!!;val d=max-min
        if(d==0.0)return 0.0
        val h=when(max) {c[0]->(c[1]-c[2])/d;c[1]->(c[2]-c[0])/d+2;else->(c[0]-c[1])/d+4}/6
        return (h%1+1)%1
    }
    fun cylindrical(c:DoubleArray,hsl:Boolean):DoubleArray {
        val max=c.maxOrNull()!!;val min=c.minOrNull()!!;val d=max-min
        val v=if(hsl)(max+min)/2 else max
        val saturation=if(d==0.0)0.0 else if(hsl)d/(1-abs(2*v-1)) else d/max
        return doubleArrayOf(hue(c),saturation,v)
    }
    fun fromCylindrical(c:DoubleArray,hsl:Boolean):DoubleArray {
        val h=((c[0]%1+1)%1)*6;val chroma=if(hsl)(1-abs(2*c[2]-1))*c[1] else c[2]*c[1]
        val x=chroma*(1-abs(h%2-1));val m=if(hsl)c[2]-chroma/2 else c[2]-chroma
        val a=when(h.toInt()) {0->doubleArrayOf(chroma,x,0.0);1->doubleArrayOf(x,chroma,0.0)
            2->doubleArrayOf(0.0,chroma,x);3->doubleArrayOf(0.0,x,chroma);4->doubleArrayOf(x,0.0,chroma)
            else->doubleArrayOf(chroma,0.0,x)}
        return DoubleArray(3) {a[it]+m}
    }
}
