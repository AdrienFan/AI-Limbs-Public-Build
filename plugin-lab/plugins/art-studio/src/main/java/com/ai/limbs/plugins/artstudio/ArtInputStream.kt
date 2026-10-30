package com.ai.limbs.plugins.artstudio

import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Bounded reads on every supported Android version; InputStream.readNBytes requires API33. */
internal fun InputStream.artReadUpTo(limit:Int):ByteArray {
    require(limit>=0)
    val output=ByteArrayOutputStream(minOf(limit,8192));val buffer=ByteArray(minOf(limit,8192))
    while(output.size()<limit) {
        val count=read(buffer,0,minOf(buffer.size,limit-output.size()))
        if(count<0)break
        check(count>0) {"输入流没有读取进展"}
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
