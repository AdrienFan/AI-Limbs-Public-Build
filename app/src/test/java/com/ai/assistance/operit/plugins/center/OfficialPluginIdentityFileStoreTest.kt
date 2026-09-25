package com.ai.assistance.operit.plugins.center

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialPluginIdentityFileStoreTest {
    @Test
    fun separateStoreInstancesObserveLatestCommittedContentsImmediately() {
        val root = Files.createTempDirectory("official-identity-store-test").toFile()
        try {
            val file = File(root, "official_plugin_identity_registry_v2.json")
            val coreStore = OfficialPluginIdentityFileStore(file)
            val workerStore = OfficialPluginIdentityFileStore(file)

            val first = """[{"plugin_id":"plugin.test.one"}]"""
            coreStore.withExclusiveLock {
                coreStore.writeRaw(first)
            }

            assertTrue(workerStore.exists())
            assertEquals(first, workerStore.readRaw())

            val second = """[{"plugin_id":"plugin.test.one"},{"plugin_id":"plugin.test.two"}]"""
            coreStore.withExclusiveLock {
                coreStore.writeRaw(second)
            }

            assertEquals(second, workerStore.readRaw())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }
}
