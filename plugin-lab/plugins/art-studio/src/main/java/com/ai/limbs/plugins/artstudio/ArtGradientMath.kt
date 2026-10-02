package com.ai.limbs.plugins.artstudio

import kotlin.math.*

/** Plugin-native analytic fields and an exact grid Euclidean distance transform; no Android dependency. */
internal object ArtGradientMath {
    class Field(private val mode:String,private val x0:Double,private val y0:Double,x1:Double,y1:Double) {
        private val dx=x1-x0;private val dy=y1-y0;private val length=hypot(dx,dy)
        private val length2=length*length;private val heading=atan2(dy,dx)
        init {require(length>=0.01)}
        fun value(x:Double,y:Double):Double {
            val px=x-x0;val py=y-y0
            val projection=(px*dx+py*dy)/length2
            return when(mode) {
                "linear"->projection;"bilinear"->abs(projection);"radial"->hypot(px,py)/length
                "square"->maxOf(abs(projection),abs((-px*dy+py*dx)/length2))
                "angular","symmetric_conical","spiral","reverse_spiral"-> {
                    val angle=((atan2(py,px)-heading)/(2*PI)).let {it-floor(it)}
                    when(mode) {"angular"->angle;"symmetric_conical"->1-abs(2*angle-1)
                        "spiral"->hypot(px,py)/length+angle;else->hypot(px,py)/length+1-angle}
                }
                else->error("非解析渐变形状")
            }
        }
    }
    fun value(mode:String,x:Double,y:Double,x0:Double,y0:Double,x1:Double,y1:Double)=Field(mode,x0,y0,x1,y1).value(x,y)
    fun repeat(value:Double,mode:String,spiral:Boolean=false):Double=when(mode) {
        "none"->value.coerceIn(0.0,1.0)
        "forward"->value-floor(value)
        "alternate"->if(spiral)1-abs(2*(value-floor(value))-1) else {
            val t=value-2*floor(value/2);if(t<=1)t else 2-t
        }
        else->error("渐变重复方式无效")
    }
    fun noise(x:Int,y:Int,seed:Int,salt:Int):Double {
        var h=x*374761393+y*668265263+seed*1442695041+salt*1274126177
        h=(h xor (h ushr 13))*1274126177;h=h xor (h ushr 16)
        return (h ushr 8)/16777216.0-0.5
    }
    /** Boundary=1, each four-connected island's deepest ridge=0; holes are exterior seeds. */
    fun contour(mask:ByteArray,w:Int,h:Int):FloatArray {
        require(w>0&&h>0&&w.toLong()*h==mask.size.toLong()&&mask.size<=ArtGradient.MAX_PIXELS)
        val pw=w+2;val ph=h+2;val inf=1e20
        val distance=DoubleArray(pw*ph) {i->val x=i%pw-1;val y=i/pw-1
            if(x !in 0 until w||y !in 0 until h||mask[y*w+x].toInt()==0)0.0 else inf}
        val len=maxOf(pw,ph);val f=DoubleArray(len);val d=DoubleArray(len);val v=IntArray(len);val z=DoubleArray(len+1)
        fun transform(n:Int) {
            var k=0;v[0]=0;z[0]=Double.NEGATIVE_INFINITY;z[1]=Double.POSITIVE_INFINITY
            for(q in 1 until n) {
                var s=((f[q]+q.toDouble()*q)-(f[v[k]]+v[k].toDouble()*v[k]))/(2*(q-v[k]))
                while(s<=z[k]) {k--;s=((f[q]+q.toDouble()*q)-(f[v[k]]+v[k].toDouble()*v[k]))/(2*(q-v[k]))}
                k++;v[k]=q;z[k]=s;z[k+1]=Double.POSITIVE_INFINITY
            }
            k=0
            for(q in 0 until n) {while(z[k+1]<q)k++;d[q]=(q-v[k]).toDouble().pow(2)+f[v[k]]}
        }
        for(y in 0 until ph) {for(x in 0 until pw)f[x]=distance[y*pw+x];transform(pw);for(x in 0 until pw)distance[y*pw+x]=d[x]}
        for(x in 0 until pw) {for(y in 0 until ph)f[y]=distance[y*pw+x];transform(ph);for(y in 0 until ph)distance[y*pw+x]=d[y]}
        val values=FloatArray(w*h) {i->maxOf(0.0,sqrt(distance[(i/w+1)*pw+i%w+1])-1).toFloat()}
        val visited=BooleanArray(w*h);val queue=IntArray(w*h)
        for(seed in mask.indices) {
            if(mask[seed].toInt()==0||visited[seed])continue
            var head=0;var tail=1;queue[0]=seed;visited[seed]=true;var peak=0f
            while(head<tail) {
                val i=queue[head++];peak=maxOf(peak,values[i]);val x=i%w;val y=i/w
                fun visit(j:Int) {if(mask[j].toInt()!=0&&!visited[j]) {visited[j]=true;queue[tail++]=j}}
                if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
            }
            for(k in 0 until tail) {val i=queue[k];values[i]=if(peak==0f)1f else (1-values[i]/peak).coerceIn(0f,1f)}
        }
        return values
    }
}
