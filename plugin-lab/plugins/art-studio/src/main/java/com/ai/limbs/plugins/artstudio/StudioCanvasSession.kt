package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption

/** Revocation is a lifecycle event, so pending page coroutines terminate without a fatal exception. */
internal class StudioCanvasReleasedException : CancellationException("临时画布已释放")

internal class StudioCanvasSession(private val root: File) {
    fun requireActive() {
        if (!File(root, ".session-active").isFile) throw StudioCanvasReleasedException()
    }
    fun openLockChannel(): FileChannel {
        requireActive()
        try {
            // WRITE without CREATE cannot resurrect a lock after the owning parent deletes it.
            return FileChannel.open(File(root, "art-studio.lock").toPath(), StandardOpenOption.WRITE)
        } catch (missing: NoSuchFileException) {
            requireActive()
            throw missing
        }
    }
}

/** Publish image ownership and the canvas in the same snapshot, including during round teardown. */
internal fun studioInteractiveDocument(panel: JSONObject, owner: String, canvas: JSONObject?): JSONObject {
    val document = JSONObject(panel.toString())
    if (document.optBoolean("image") && (canvas == null || canvas.getString("owner") != owner ||
            !canvas.getBoolean("frozen"))) document.remove("image")
    return document
}
