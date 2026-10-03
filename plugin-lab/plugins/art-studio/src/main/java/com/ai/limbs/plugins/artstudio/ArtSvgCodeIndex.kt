package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** UTF-16 offsets agree with EditText and API paging; quoted '>' never terminates a tag. */
internal object ArtSvgCodeIndex {
    data class Range(val id:String,val start:Int,val end:Int) {fun json()=JSONObject().put("id",id).put("start",start).put("end",end)}
    private data class Open(val name:String,val id:String?,val start:Int)
    fun ranges(source:String):List<Range> {
        val stack=mutableListOf<Open>();val result=mutableListOf<Range>();var at=0;var nodes=0
        while(at<source.length) {
            val start=source.indexOf('<',at);if(start<0)break
            if(source.startsWith("<!--",start)||source.startsWith("<![CDATA[",start)) {
                val endMark=if(source.startsWith("<!--",start))"-->" else "]]>"
                val end=source.indexOf(endMark,start+4);require(end>=0){"代码块尚未闭合"};at=end+endMark.length;continue
            }
            var end=start+1;var quote:Char?=null
            while(end<source.length) {val c=source[end];if(quote!=null){if(c==quote)quote=null}else if(c=='\''||c=='\"')quote=c else if(c=='>')break;end++}
            require(end<source.length){"标签尚未闭合"};val tag=source.substring(start+1,end).trim();at=end+1
            if(tag.startsWith('?'))continue
            require(!tag.startsWith('!')) {"不支持的 XML 声明"}
            val closing=tag.startsWith('/');val name=tag.removePrefix("/").takeWhile {!it.isWhitespace()&&it!='/'}
            if(closing) {
                require(stack.isNotEmpty()&&stack.last().name==name){"标签闭合不匹配"};val open=stack.removeAt(stack.lastIndex)
                open.id?.let {result.add(Range(it,open.start,at))}
            } else {
                require(++nodes<=8192&&stack.size<=32){"SVG结构超过8192节点或32层嵌套"}
                val id=Regex("(?:^|\\s)id\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)
                if(tag.endsWith('/'))id?.let {result.add(Range(it,start,at))} else stack.add(Open(name,id,start))
            }
        }
        require(stack.isEmpty()){ "代码块尚未闭合" };require(result.map {it.id}.distinct().size==result.size){"SVG id 重复"}
        return result.sortedBy {it.start}
    }
    fun json(source:String)=JSONArray(ranges(source).map {it.json()})
    fun at(ranges:List<Range>,offset:Int)=ranges.filter {offset in it.start until it.end}.minByOrNull {it.end-it.start}
}
