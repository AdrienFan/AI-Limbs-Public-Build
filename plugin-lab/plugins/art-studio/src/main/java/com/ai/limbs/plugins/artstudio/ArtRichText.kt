package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Interval edits used by the phone editor; styles survive insertions and replacements. */
internal object ArtRichText {
    private fun styles(content: String, spans: JSONArray): Array<JSONObject?> {
        val styles=arrayOfNulls<JSONObject>(content.length)
        for(i in 0 until spans.length()) {
            val span=spans.getJSONObject(i);val s=JSONObject(span.toString()).apply {remove("start");remove("end")}
            for(index in span.getInt("start") until span.getInt("end"))styles[index]=s
        }
        return styles
    }
    private fun encode(styles: Array<JSONObject?>): JSONArray {
        val result=JSONArray();var start=0
        while(start<styles.size) {
            val style=styles[start];var end=start+1
            while(end<styles.size&&styles[end]?.toString()==style?.toString())end++
            style?.let {result.put(JSONObject(it.toString()).put("start",start).put("end",end))}
            start=end
        }
        require(result.length()<=128) {"富文本样式段超过 128 个"};return result
    }
    fun apply(content:String,spans:JSONArray,start:Int,end:Int,style:JSONObject):JSONArray {
        require(start>=0&&end>start&&end<=content.length) {"请先选中要设置样式的文字"}
        val states=styles(content,spans)
        for(i in start until end)states[i]=JSONObject(states[i]?.toString() ?: "{}").also {p->style.keys().forEach {p.put(it,style.get(it))}}
        return encode(states)
    }
    fun edit(before:String,after:String,spans:JSONArray):JSONArray {
        if(before==after)return JSONArray(spans.toString())
        var prefix=0
        while(prefix<minOf(before.length,after.length)&&before[prefix]==after[prefix])prefix++
        if(prefix>0&&prefix<before.length&&Character.isLowSurrogate(before[prefix]))prefix--
        var suffix=0
        while(suffix<before.length-prefix&&suffix<after.length-prefix&&before[before.length-1-suffix]==after[after.length-1-suffix])suffix++
        if(suffix>0&&before.length-suffix>0&&Character.isLowSurrogate(before[before.length-suffix]))suffix--
        val states=styles(before,spans);val result=arrayOfNulls<JSONObject>(after.length)
        for(i in 0 until prefix)result[i]=states[i]
        val insertedStyle=if(prefix<states.size)states[prefix] else states.lastOrNull()
        for(i in prefix until after.length-suffix)result[i]=insertedStyle
        for(i in 0 until suffix)result[after.length-1-i]=states[before.length-1-i]
        return encode(result)
    }
}
