package com.ai.limbs.plugins.artstudio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.*

/** OpenEXR v2 single-part scanlines: RGB(A)/Y, HALF/FLOAT, NONE/ZIPS/ZIP.
 * The project encoder writes lossless ZIP HALF linear Rec.709, including alpha and signed HDR.
 * Unsupported layouts/codecs are explicit errors; no 8-bit decoder participates in this path.
 */
internal object ArtOpenExr {
    const val MAGIC=20000630
    data class Channel(val name:String,val type:Int)
    data class Header(val width:Int,val height:Int,val x:Int,val y:Int,val channels:List<Channel>,val compression:Int,
        val table:Int,val rgbMatrix:DoubleArray)
    data class Image(val header:Header,val pixels:FloatArray)
    private val rec709=doubleArrayOf(.64,.33,.30,.60,.15,.06,.3127,.3290)
    fun matches(bytes:ByteArray)=bytes.size>=4 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int==MAGIC
    private fun buffer(bytes:ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun string(b:ByteBuffer,max:Int=255):String {
        val out=ByteArrayOutputStream()
        while(true) {
            require(b.hasRemaining() && out.size()<=max) {"EXR字符串无效"}
            val v=b.get().toInt() and 255;if(v==0)break
            require(v in 32..126) {"EXR名称须为ASCII"};out.write(v)
        }
        return out.toString(Charsets.US_ASCII.name())
    }
    fun header(bytes:ByteArray):Header {
        require(bytes.size>=9 && matches(bytes)) {"不是完整OpenEXR图片"}
        val b=buffer(bytes);b.int;val version=b.int
        require(version==2) {"仅支持EXR v2单部件扫描线图片；不支持平铺、深度或多部件布局"}
        val attributes=mutableMapOf<String,Pair<String,ByteArray>>()
        while(true) {
            val name=string(b);if(name.isEmpty())break
            val type=string(b);require(b.remaining()>=4)
            val size=b.int;require(size in 0..1048576 && size<=b.remaining() && attributes.size<128 && name !in attributes)
            attributes[name]=type to ByteArray(size).also {b.get(it)}
        }
        fun attr(name:String,type:String):ByteBuffer {
            val a=attributes[name] ?: error("EXR缺少必需属性："+name)
            require(a.first==type);return buffer(a.second)
        }
        val window=attr("dataWindow","box2i");require(window.remaining()==16)
        val x=window.int;val y=window.int;val right=window.int;val bottom=window.int
        val width=right.toLong()-x+1;val height=bottom.toLong()-y+1
        require(width in 1..16384 && height in 1..16384) {"EXR数据窗口边长需要1–16384像素"}
        val compression=attr("compression","compression");require(compression.remaining()==1)
        val codec=compression.get().toInt() and 255;require(codec in setOf(0,2,3)) {"EXR目前支持NONE、ZIPS、ZIP无损压缩"}
        val channels=mutableListOf<Channel>();val raw=attr("channels","chlist")
        while(true) {
            val name=string(raw);if(name.isEmpty())break
            require(raw.remaining()>=16 && name !in channels.map {it.name} && channels.size<4)
            val type=raw.int;raw.int;val sx=raw.int;val sy=raw.int
            require(type in setOf(1,2) && sx==1 && sy==1) {"EXR通道须为HALF/FLOAT、1×1采样"}
            channels.add(Channel(name,type))
        }
        require(!raw.hasRemaining() && channels.map {it.name}.toSet() in setOf(setOf("R","G","B"),setOf("A","R","G","B"),setOf("Y"),setOf("A","Y"))) {"EXR须为单组RGB(A)或Y(A)通道"}
        val chroma=attributes["chromaticities"]?.let {a->require(a.first=="chromaticities" && a.second.size==32)
            val values=buffer(a.second);DoubleArray(8) {values.float.toDouble()}} ?: rec709
        val lines=if(codec==3)16 else 1;val blocks=(height.toInt()+lines-1)/lines
        require(b.position().toLong()+blocks*8<=bytes.size)
        return Header(width.toInt(),height.toInt(),x,y,channels,codec,b.position(),chromaticMatrix(chroma))
    }
    fun decode(bytes:ByteArray,maxPixels:Long,targetWidth:Int=header(bytes).width,targetHeight:Int=header(bytes).height):Image {
        val h=header(bytes);val n=targetWidth.toLong()*targetHeight
        require(targetWidth in 1..h.width && targetHeight in 1..h.height)
        require(n<=maxPixels && n*4<=Int.MAX_VALUE) {"EXR超过本次像素处理预算"}
        val b=buffer(bytes);val lines=if(h.compression==3)16 else 1;val blocks=(h.height+lines-1)/lines
        val out=FloatArray(n.toInt()*4);val seen=BooleanArray(blocks)
        val columns=IntArray(h.width) {-1};val rowsMap=IntArray(h.height) {-1}
        for(x in 0 until targetWidth)columns[((x+.5)*h.width/targetWidth).toInt().coerceAtMost(h.width-1)]=x
        for(y in 0 until targetHeight)rowsMap[((y+.5)*h.height/targetHeight).toInt().coerceAtMost(h.height-1)]=y
        for(i in 0 until n.toInt())out[i*4+3]=1f
        var previousEnd=h.table.toLong()+blocks*8
        // Canonical increasing offsets bound each chunk; reordered/overlapping tables are rejected.
        for(block in 0 until blocks) {
            val offset=b.getLong(h.table+block*8)
            require(offset>=previousEnd && offset<=bytes.size-8L)
            b.position(offset.toInt());val y=b.int.toLong()-h.y;val size=b.int
            require(y in 0 until h.height.toLong() && y%lines==0L && !seen[y.toInt()/lines])
            seen[y.toInt()/lines]=true
            val rows=minOf(lines,h.height-y.toInt());val stride=h.width*h.channels.sumOf {if(it.type==1)2 else 4}
            val expected=stride*rows
            require(size>0 && size<=bytes.size-b.position() && size<=expected+65536)
            val packed=ByteArray(size);b.get(packed);previousEnd=b.position().toLong()
            val raw=if(h.compression==0 || size==expected) {
                require(size==expected);packed
            } else unzip(packed,expected)
            val samples=buffer(raw)
            for(row in 0 until rows)for(channel in h.channels)for(x in 0 until h.width) {
                val value=if(channel.type==1)ArtHalf.float(samples.short) else samples.float
                require(value.isFinite() && abs(value)<=65504f) {"EXR样本超出有限半浮点范围"}
                val targetX=columns[x];val targetY=rowsMap[y.toInt()+row]
                if(targetX<0 || targetY<0)continue
                val index=(targetY*targetWidth+targetX)*4
                when(channel.name) {"R"->out[index]=value;"G"->out[index+1]=value;"B"->out[index+2]=value
                    "A"->{require(value in 0f..1f) {"EXR alpha须为0–1"};out[index+3]=value}
                    "Y"->{out[index]=value;out[index+1]=value;out[index+2]=value}}
            }
        }
        for(i in out.indices step 4) {
            val r=out[i];val g=out[i+1];val blue=out[i+2];val m=h.rgbMatrix
            for(c in 0..2) {
                val value=(m[c*3]*r+m[c*3+1]*g+m[c*3+2]*blue).toFloat()
                require(value.isFinite() && abs(value)<=65504f) {"EXR颜色空间转换超出RGBA_F16范围"};out[i+c]=value
            }
        }
        return Image(h.copy(width=targetWidth,height=targetHeight),out)
    }
    private fun unzip(packed:ByteArray,expected:Int):ByteArray {
        val inflater=Inflater();val temp=ByteArray(expected)
        try {
            inflater.setInput(packed);var count=0
            while(!inflater.finished() && count<expected) {
                val read=inflater.inflate(temp,count,expected-count);require(read>0) {"EXR ZIP流不完整"};count+=read
            }
            require(count==expected && inflater.finished() && inflater.remaining==0) {"EXR ZIP尺寸无效"}
        } finally {inflater.end()}
        for(i in 1 until temp.size)temp[i]=((temp[i-1].toInt() and 255)+(temp[i].toInt() and 255)-128).toByte()
        val out=ByteArray(expected);var a=0;var b=(expected+1)/2
        for(i in out.indices)out[i]=if(i%2==0)temp[a++] else temp[b++]
        return out
    }
    private fun zip(raw:ByteArray):ByteArray {
        val shuffled=ByteArray(raw.size);var a=0;var b=(raw.size+1)/2
        for(i in raw.indices)if(i%2==0)shuffled[a++]=raw[i] else shuffled[b++]=raw[i]
        for(i in shuffled.lastIndex downTo 1)shuffled[i]=((shuffled[i].toInt() and 255)-(shuffled[i-1].toInt() and 255)+128).toByte()
        val deflater=Deflater(6);val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        try {deflater.setInput(shuffled);deflater.finish();while(!deflater.finished()) {val count=deflater.deflate(buffer);check(count>0);out.write(buffer,0,count)}}
        finally {deflater.end()}
        val compressed=out.toByteArray()
        // EXR ZIP specifies raw storage when the packed block is not smaller than its raw byte count.
        return if(compressed.size<raw.size)compressed else raw
    }
    fun encode(width:Int,height:Int,pixels:FloatArray,limit:Int):ByteArray {
        require(width in 1..16384 && height in 1..16384 && width.toLong()*height*4==pixels.size.toLong() && limit>0)
        val header=ByteArrayOutputStream()
        fun int(out:ByteArrayOutputStream,value:Int) {for(i in 0..3)out.write(value ushr (i*8))}
        fun str(out:ByteArrayOutputStream,value:String) {out.write(value.toByteArray(Charsets.US_ASCII));out.write(0)}
        fun attr(name:String,type:String,value:ByteArray) {str(header,name);str(header,type);int(header,value.size);header.write(value)}
        fun ints(vararg values:Int)=ByteArrayOutputStream().also {out->values.forEach {int(out,it)}}.toByteArray()
        int(header,MAGIC);int(header,2)
        val channels=listOf("A","B","G","R")
        val channelList=ByteArrayOutputStream()
        channels.forEach {str(channelList,it);int(channelList,1);int(channelList,0);int(channelList,1);int(channelList,1)};channelList.write(0)
        attr("channels","chlist",channelList.toByteArray());attr("compression","compression",byteArrayOf(3))
        attr("dataWindow","box2i",ints(0,0,width-1,height-1));attr("displayWindow","box2i",ints(0,0,width-1,height-1))
        attr("lineOrder","lineOrder",byteArrayOf(0));attr("pixelAspectRatio","float",ints(1f.toRawBits()))
        attr("screenWindowCenter","v2f",ints(0,0));attr("screenWindowWidth","float",ints(1f.toRawBits()))
        attr("chromaticities","chromaticities",ints(*rec709.map {it.toFloat().toRawBits()}.toIntArray()))
        header.write(0)
        val blocks=(height+15)/16;val packed=ArrayList<ByteArray>(blocks);var total=header.size().toLong()+blocks*8
        for(y in 0 until height step 16) {
            check(!Thread.currentThread().isInterrupted) {"EXR编码已取消"}
            val rows=minOf(16,height-y);val raw=ByteBuffer.allocate(width*rows*8).order(ByteOrder.LITTLE_ENDIAN)
            for(row in 0 until rows)for(channel in listOf(3,2,1,0))for(x in 0 until width) {
                val index=((y+row)*width+x)*4;val alpha=pixels[index+3]
                require(alpha.isFinite() && alpha in 0f..1f)
                val value=pixels[index+channel]
                require(value.isFinite() && abs(value)<=65504f) {"EXR编码样本超出有限半浮点范围"}
                raw.putShort(ArtHalf.half(value))
            }
            val data=zip(raw.array());packed.add(data);total+=8+data.size
            require(total<=limit) {"EXR图片超过保存上限"}
        }
        val out=ByteArrayOutputStream(total.toInt());out.write(header.toByteArray());var offset=header.size().toLong()+blocks*8
        for(data in packed) {for(i in 0..7)out.write((offset ushr (8*i)).toInt());offset+=8+data.size}
        packed.forEachIndexed {i,data->int(out,i*16);int(out,data.size);out.write(data)}
        return out.toByteArray()
    }

    private fun multiply(a:DoubleArray,b:DoubleArray)=DoubleArray(9) {i->(0..2).sumOf {k->a[i/3*3+k]*b[k*3+i%3]}}
    private fun inverse(a:DoubleArray):DoubleArray {
        val d=a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6])
        require(d.isFinite() && abs(d)>1e-12) {"EXR色度矩阵不可逆"}
        return doubleArrayOf(a[4]*a[8]-a[5]*a[7],a[2]*a[7]-a[1]*a[8],a[1]*a[5]-a[2]*a[4],
            a[5]*a[6]-a[3]*a[8],a[0]*a[8]-a[2]*a[6],a[2]*a[3]-a[0]*a[5],
            a[3]*a[7]-a[4]*a[6],a[1]*a[6]-a[0]*a[7],a[0]*a[4]-a[1]*a[3]).map {it/d}.toDoubleArray()
    }
    private fun vector(a:DoubleArray,v:DoubleArray)=DoubleArray(3) {r->(0..2).sumOf {a[r*3+it]*v[it]}}
    private fun white(c:DoubleArray)=doubleArrayOf(c[6]/c[7],1.0,(1-c[6]-c[7])/c[7])
    private fun rgbToXyz(c:DoubleArray):DoubleArray {
        require(c.size==8 && c.all {it.isFinite()} && (0..3).all {c[it*2+1]>0}) {"EXR色度属性无效"}
        val matrix=DoubleArray(9) {i->val x=c[i%3*2];val y=c[i%3*2+1];when(i/3) {0->x/y;1->1.0;else->(1-x-y)/y}}
        val scales=vector(inverse(matrix),white(c));return DoubleArray(9) {matrix[it]*scales[it%3]}
    }
    private fun chromaticMatrix(c:DoubleArray):DoubleArray {
        val source=rgbToXyz(c);val destination=rgbToXyz(rec709)
        val bradford=doubleArrayOf(.8951,.2664,-.1614,-.7502,1.7135,.0367,.0389,-.0685,1.0296)
        val sw=vector(bradford,white(c));val dw=vector(bradford,white(rec709))
        val scaled=DoubleArray(9) {bradford[it]*dw[it/3]/sw[it/3]}
        return multiply(inverse(destination),multiply(multiply(inverse(bradford),scaled),source))
    }
}
