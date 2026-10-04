package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class StudioCanvasSessionTest {
    private fun sessionRoot(): File = Files.createTempDirectory("art-session-").toFile().also { root ->
        for (name in listOf("drafts", "documents", "assets", "templates", "backups")) File(root, name).mkdir()
        File(root, "art-studio.lock").writeBytes(byteArrayOf())
        File(root, ".session-active").writeText("test-token")
    }
    @Test fun releasedMenuPollCancelsWithoutFailingItsParentOrRecreatingFiles() = runBlocking {
        val root = sessionRoot()
        try {
            val store = ArtStore(root, ephemeral = true)
            val waiting = CountDownLatch(1); val resume = CountDownLatch(1)
            val reachedMenuBody = AtomicBoolean(false)
            val poll = launch(Dispatchers.IO) {
                waiting.countDown(); resume.await()
                store.menuUiState()
                reachedMenuBody.set(true)
            }
            waiting.await()
            store.revokeEphemeral(); assertTrue(root.deleteRecursively())
            resume.countDown(); poll.join()
            assertTrue(poll.isCancelled)
            assertTrue(isActive)
            assertFalse(reachedMenuBody.get())
            assertFalse(root.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun delayedFirstCompositionCannotRecreateReleasedSession() {
        val root = sessionRoot(); root.deleteRecursively()
        try { ArtStore(root, ephemeral = true); fail("Expected cancelled session construction") }
        catch (_: StudioCanvasReleasedException) { }
        assertFalse(root.exists())
    }
    @Test fun revokedReaderCannotRecreateDeletedLock() {
        val root = sessionRoot()
        try {
            val session = StudioCanvasSession(root)
            session.requireActive()
            File(root, ".session-active").delete(); File(root, "art-studio.lock").delete()
            try { session.openLockChannel(); fail("Expected cancelled read") }
            catch (_: StudioCanvasReleasedException) { }
            assertFalse(File(root, "art-studio.lock").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun missingLockInAnActiveSessionRemainsAnExplicitStorageError() {
        val root = sessionRoot()
        try {
            File(root, "art-studio.lock").delete()
            try { StudioCanvasSession(root).openLockChannel(); fail("Expected missing lock error") }
            catch (_: NoSuchFileException) { }
            assertFalse(File(root, "art-studio.lock").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun ordinaryDocumentDoesNotRequireTemporarySessionMarkers() {
        val root = Files.createTempDirectory("ordinary-art-").toFile()
        try {
            val store = ArtStore(root)
            assertEquals("", store.revision())
            assertTrue(File(root, "drafts").isDirectory)
            assertFalse(File(root, ".session-active").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun imageAndCanvasOwnershipArePublishedTogetherWithoutMutatingChildState() {
        val panel = JSONObject().put("image", true).put("title", "Game")
        val frozen = JSONObject().put("owner", "game").put("frozen", true)
        assertTrue(studioInteractiveDocument(panel, "game", frozen).getBoolean("image"))
        assertFalse(studioInteractiveDocument(panel, "game", null).has("image"))
        assertFalse(studioInteractiveDocument(panel, "other", frozen).has("image"))
        assertFalse(studioInteractiveDocument(panel, "game", frozen.put("frozen", false)).has("image"))
        assertTrue(panel.getBoolean("image"))
    }
}
