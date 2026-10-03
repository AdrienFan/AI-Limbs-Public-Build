package com.ai.limbs.plugins.artstudio

import java.io.File

/** A render owns handles, not live asset paths that can be atomically replaced by an import. */
internal interface StudioAssetHandle : AutoCloseable {
    val file:File
}

internal class StudioAssetLease private constructor(private val handles:Map<String,StudioAssetHandle>) : AutoCloseable {
    val files:Map<String,File> = handles.mapValues {it.value.file}
    private var closed=false

    override fun close() {
        if(closed)return
        closed=true
        closeAll(handles.values,null)?.let {throw it}
    }

    companion object {
        fun capture(ids:Iterable<String>,open:(String)->StudioAssetHandle):StudioAssetLease {
            val handles=linkedMapOf<String,StudioAssetHandle>()
            try {
                for(id in ids)if(id !in handles)handles[id]=open(id)
                return StudioAssetLease(handles.toMap())
            } catch(error:Throwable) {
                closeAll(handles.values,error)
                throw error
            }
        }

        private fun closeAll(handles:Collection<StudioAssetHandle>,primary:Throwable?):Throwable? {
            var error=primary
            for(handle in handles)try {handle.close()} catch(failure:Throwable) {
                val previous=error
                if(previous==null)error=failure else if(previous!==failure)previous.addSuppressed(failure)
            }
            return error
        }
    }
}
