package ksubprocess

import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.windows.AcquireSRWLockExclusive
import platform.windows.InitializeSRWLock
import platform.windows.ReleaseSRWLockExclusive
import platform.windows.SRWLOCK
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

internal actual class NativeLock actual constructor() {
    private val srwLock = nativeHeap.alloc<SRWLOCK>().also { InitializeSRWLock(it.ptr) }

    // an SRW lock needs no destruction, only its memory is released
    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(srwLock) { nativeHeap.free(it) }

    actual fun lock() {
        AcquireSRWLockExclusive(srwLock.ptr)
    }

    actual fun unlock() {
        ReleaseSRWLockExclusive(srwLock.ptr)
    }
}
