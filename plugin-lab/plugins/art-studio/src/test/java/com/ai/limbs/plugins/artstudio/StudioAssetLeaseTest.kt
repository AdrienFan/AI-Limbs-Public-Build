package com.ai.limbs.plugins.artstudio

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Resource ownership checks run in cloud CI; Android descriptor decoding needs device acceptance. */
class StudioAssetLeaseTest {
    private class Handle(val id:String,val releaseFailure:IOException?=null):StudioAssetHandle {
        override val file=File("/frozen/$id")
        var closes=0
        override fun close() {closes++;releaseFailure?.let {throw it}}
    }

    @Test fun repeatedAssetsAcquireOnceAndAllHandlesCloseExactlyOnce() {
        val opened=linkedMapOf<String,Handle>()
        val lease=StudioAssetLease.capture(listOf("paper","brush","paper")) {id->
            check(id !in opened);Handle(id).also {opened[id]=it}
        }
        assertEquals(setOf("paper","brush"),lease.files.keys)
        assertEquals(File("/frozen/paper"),lease.files.getValue("paper"))
        lease.close();lease.close()
        assertTrue(opened.values.all {it.closes==1})
    }

    @Test fun failedAcquisitionReleasesEarlierHandlesAndPreservesThePrimaryError() {
        val release=IOException("close error")
        val first=Handle("first",release);val second=Handle("second")
        val failure=IOException("asset unavailable")
        try {
            StudioAssetLease.capture(listOf("first","second","missing")) {id->
                when(id) {"first"->first;"second"->second;else->throw failure}
            }
            fail("Must propagate acquisition error")
        } catch(actual:IOException) {
            assertSame(failure,actual);assertEquals(listOf(release),actual.suppressed.toList())
        }
        assertEquals(1,first.closes);assertEquals(1,second.closes)
    }

    @Test fun oneCloseErrorDoesNotLeakOtherResourcesOrRecloseAlreadyReleasedHandles() {
        val first=Handle("first",IOException("first close failed"));val second=Handle("second")
        val lease=StudioAssetLease.capture(listOf("first","second")) {id->if(id=="first")first else second}
        try {lease.close();fail("Must report close error")}
        catch(actual:IOException) {assertSame(first.releaseFailure,actual)}
        assertEquals(1,first.closes);assertEquals(1,second.closes)
        lease.close();assertEquals(1,second.closes)
    }
}
