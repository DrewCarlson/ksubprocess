package ksubprocess

import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

internal actual class NativeLock actual constructor() {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        check(pthread_mutex_init(it.ptr, null) == 0) { "pthread_mutex_init failed" }
    }

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(mutex) {
        pthread_mutex_destroy(it.ptr)
        nativeHeap.free(it)
    }

    actual fun lock() {
        check(pthread_mutex_lock(mutex.ptr) == 0) { "pthread_mutex_lock failed" }
    }

    actual fun unlock() {
        check(pthread_mutex_unlock(mutex.ptr) == 0) { "pthread_mutex_unlock failed" }
    }
}
