package com.ai.limbs.plugins.artstudio

/** Separate snapshot ownership from pixel ownership when multiple revisions show the same image. */
internal class StudioSharedResource<T : Any>(val value: T, private val dispose: (T) -> Unit) {
    private var references = 1
    @Synchronized fun retain(): StudioSharedResource<T> {
        check(references > 0) { "显示资源已释放" }
        references++
        return this
    }
    @Synchronized fun release() {
        check(references > 0) { "显示资源已释放" }
        if (--references == 0) dispose(value)
    }
}
