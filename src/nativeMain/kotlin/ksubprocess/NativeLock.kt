package ksubprocess

/** A non-reentrant mutex for code that must not suspend, such as property getters and cleaners. */
internal expect class NativeLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> NativeLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
