package com.ai.assistance.operit.core.tools.system.resident

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** A process-held lease. Never unlink its file: a new inode would allow two owners. */
internal class ResidentRuntimeLease private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock
) : Closeable {
    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            file.close()
        }
    }

    companion object {
        fun tryAcquire(directory: File, name: String): ResidentRuntimeLease? {
            require(name.matches(Regex("[a-z_]+")))
            check(directory.mkdirs() || directory.isDirectory) {
                "Cannot create runtime lease directory"
            }
            val file = RandomAccessFile(File(directory, "$name.lock"), "rw")
            try {
                val lock = try {
                    file.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    file.close()
                    return null
                }
                return ResidentRuntimeLease(file, lock)
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }

        fun acquire(directory: File, name: String): ResidentRuntimeLease =
            checkNotNull(tryAcquire(directory, name)) {
                "Runtime owner already exists: $name"
            }
    }
}
