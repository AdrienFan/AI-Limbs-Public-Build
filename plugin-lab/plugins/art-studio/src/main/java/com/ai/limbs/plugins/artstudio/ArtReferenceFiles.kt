package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.content.ContentResolver
import android.content.ClipboardManager
import android.net.Uri
import java.io.*
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit source reads and a portable plugin-owned reference collection, never artwork export. */
internal object ArtReferenceFiles {
    const val IMAGE_BYTES=8*1024*1024
    const val COLLECTION_BYTES=32*1024*1024
    const val MANIFEST_BYTES=1024*1024
    const val FORMAT="AIL_REFERENCE_COLLECTION_V1"
    fun readLimited(input:InputStream,limit:Int):ByteArray {
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val n=input.read(buffer);if(n<0)break
            require(output.size().toLong()+n<=limit) {"输入超过允许大小：$limit 字节"};output.write(buffer,0,n)}
        return output.toByteArray()
    }
    fun location(value:String):URI {
        require(value.isNotBlank()&&value.length<=4096&&!value.contains('\u0000')) {"图片来源无效"}
        val uri=if(value.startsWith("/"))File(value).toURI() else URI(value)
        require(uri.scheme in setOf("https","file","content")&&uri.userInfo==null) {"来源须为HTTPS图片地址、绝对文件路径或content URI"}
        if(uri.scheme=="https")require(!uri.host.isNullOrBlank())
        if(uri.scheme=="file")require((uri.host.isNullOrEmpty()||uri.host=="localhost")&&File(uri.path).isAbsolute)
        return uri
    }
    fun read(value:String,resolver:ContentResolver?=null):ByteArray {
        val uri=location(value)
        return when(uri.scheme) {
            "file"->FileInputStream(File(uri.path)).use {readLimited(it,IMAGE_BYTES)}
            "content"->{val r=requireNotNull(resolver) {"content URI须通过手机授权读取，或显式提供base64；后台不会借用手机权限"}
                val input=r.openInputStream(Uri.parse(uri.toString())) ?: error("无法打开图片URI")
                input.use {readLimited(it,IMAGE_BYTES)}}
            "https"->{var target=uri.toURL();var result:ByteArray?=null
                for(redirect in 0..3) {
                    val c=target.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=15000;c.setRequestProperty("Accept","image/*")
                    try {val status=c.responseCode
                        if(status in setOf(301,302,303,307,308)) {
                            require(redirect<3) {"图片链接重定向过多"}
                            val next=URL(target,c.getHeaderField("Location") ?: error("重定向缺少地址"))
                            require(location(next.toString()).scheme=="https") {"图片链接不能跳转到非HTTPS来源"};target=next
                        } else {
                            require(status==200) {"图片链接读取失败：HTTP $status"}
                            require(c.contentLengthLong<=IMAGE_BYTES) {"链接图片超过8 MiB"}
                            result=c.inputStream.use {readLimited(it,IMAGE_BYTES)};break
                        }
                    } finally {c.disconnect()}
                }
                requireNotNull(result)}
            else->error("图片来源类型无效")
        }
    }
    /** Called by the focused phone presentation on user action, never by a background Resident. */
    fun clipboard(context:Context):ByteArray {
        val manager=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip=manager.primaryClip ?: error("系统剪贴板没有可读取的图片；请在画室前台粘贴")
        require(clip.itemCount>0);val item=clip.getItemAt(0)
        val source=item.uri?.toString() ?: item.text?.toString()?.trim() ?: error("剪贴板首项不是图片URI或图片链接")
        return read(source,context.contentResolver)
    }
    private fun manifest(refs:List<JSONObject>,keepLinks:Boolean):JSONObject = JSONObject().put("format",FORMAT)
        .put("references",JSONArray(refs.mapIndexed {i,ref->JSONObject(ref.toString()).apply {
            remove("id");remove("asset");put("image","images/$i.png")
            if(!keepLinks) {remove("externalSource");put("storage","embedded")}
        }}))
    fun encode(refs:List<JSONObject>,keepLinks:Boolean,asset:(String)->ByteArray):ByteArray {
        require(refs.size in 1..16)
        val output=ByteArrayOutputStream()
        val bounded=object:OutputStream() {
            override fun write(b:Int) {require(output.size()<COLLECTION_BYTES) {"集合超过32 MiB"};output.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int) {require(output.size().toLong()+len<=COLLECTION_BYTES) {"集合超过32 MiB"};output.write(b,off,len)}
        }
        var total=0L
        ZipOutputStream(bounded).use {zip->
            val metadata=manifest(refs,keepLinks).toString().toByteArray(Charsets.UTF_8)
            require(metadata.size<=MANIFEST_BYTES);total+=metadata.size
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(metadata);zip.closeEntry()
            refs.forEachIndexed {i,ref->val bytes=asset(ref.getString("asset"));require(bytes.size in 1..IMAGE_BYTES)
                total+=bytes.size;require(total<=COLLECTION_BYTES) {"集合展开后超过32 MiB"}
                zip.putNextEntry(ZipEntry("images/$i.png"));zip.write(bytes);zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    data class Collection(val references:List<JSONObject>,val images:Map<String,ByteArray>)
    fun decode(bytes:ByteArray):Collection {
        require(bytes.size in 1..COLLECTION_BYTES)
        val entries=linkedMapOf<String,ByteArray>();var total=0L
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->while(true) {
            val e=zip.nextEntry ?: break
            require(!e.isDirectory&&(e.name=="manifest.json"||Regex("images/(?:[0-9]|1[0-5])\\.png").matches(e.name))) {"集合包含未知条目或非法路径"}
            require(e.name !in entries&&entries.size<17) {"集合条目重复或数量超限"}
            val limit=if(e.name=="manifest.json")MANIFEST_BYTES else IMAGE_BYTES
            val content=readLimited(zip,minOf(limit,(COLLECTION_BYTES-total).toInt()))
            total+=content.size;entries[e.name]=content;zip.closeEntry()
        }}
        val metadata=JSONObject(String(requireNotNull(entries.remove("manifest.json")) {"集合缺少manifest.json"},Charsets.UTF_8))
        require(metadata.getString("format")==FORMAT) {"仅支持本画室.ailrefs集合；不兼容Krita.krf"}
        val a=metadata.getJSONArray("references");require(a.length() in 1..16)
        val refs=(0 until a.length()).map {a.getJSONObject(it)}
        val names=refs.map {it.getString("image")};require(names.distinct().size==names.size&&names.toSet()==entries.keys)
        val signature=byteArrayOf(-119,80,78,71,13,10,26,10)
        for(data in entries.values)require(data.size>=8&&data.copyOfRange(0,8).contentEquals(signature)) {"集合图片须为PNG"}
        return Collection(refs,entries)
    }
    fun info()=JSONObject().put("collectionFormat",FORMAT).put("extension",".ailrefs").put("maxReferences",16)
        .put("maxImageBytes",IMAGE_BYTES).put("maxCollectionBytes",COLLECTION_BYTES)
        .put("sourceSchemes",JSONArray(listOf("https","file","content"))).put("defaultKeepLinks",false)
        .put("linkPolicy","Saved PNG snapshot + externalSource; only explicit refresh reads source; failures do not replace saved reference")
        .put("clipboard","Phone foreground: first image URI/image URL; AI reference.paste: studio clipboard or supplied image base64; no background system clipboard read")
        .put("collectionPolicy","Images/order/matrix/style; import appends all in one undoable operation; new IDs; keepLinks false creates embedded portable copies")
}
