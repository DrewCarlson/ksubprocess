package ksubprocess.io

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.buffered
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Minimal internal cross-platform file/pipe handle abstraction.
 */
@OptIn(UnsafeIoApi::class)
internal abstract class KFileHandle(
    private val readWrite: Boolean,
) : AutoCloseable {
    private var closed: Boolean = false
    private var openStreamCount = 0

    private val lock = reentrantLock()

    /**
     * Returns a source that reads from this starting at [fileOffset]. The returned source must be
     * closed when it is no longer needed.
     */
    @Throws(IOException::class)
    fun source(fileOffset: Long = 0L): Source {
        lock.withLock {
            check(!closed) { "closed" }
            openStreamCount++
        }
        return FileHandleSource(this, fileOffset).buffered()
    }


    @Throws(IOException::class)
    fun sink(fileOffset: Long = 0L): Sink {
        check(readWrite) { "file handle is read-only" }
        lock.withLock {
            check(!closed) { "closed" }
            openStreamCount++
        }
        return FileHandleSink(this, fileOffset).buffered()
    }

    /** Close this handle. It is safe to call multiple times. */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            if (openStreamCount != 0) return
        }
        protectedClose()
    }

    /** Flush any buffered data to the underlying handle. */
    fun flush() {
        checkOpen()
        checkWriteAllowed()
        protectedFlush()
    }

    /** Return the size of the underlying file if known. */
    fun size(): Long {
        checkOpen()
        return protectedSize()
    }

    /** Resize the underlying file to [size]. */
    fun resize(size: Long) {
        checkOpen()
        checkWriteAllowed()
        require(size >= 0) { "size < 0" }
        protectedResize(size)
    }

    /**
     * Read up to [byteCount] bytes starting at [fileOffset] into [array] at [arrayOffset].
     * Returns the number of bytes read, or -1 on EOF.
     */
    fun read(fileOffset: Long, array: ByteArray, arrayOffset: Int = 0, byteCount: Int = array.size - arrayOffset): Int {
        checkOpen()
        checkBounds(array.size, arrayOffset, byteCount)
        require(fileOffset >= 0) { "fileOffset < 0" }
        return protectedRead(fileOffset, array, arrayOffset, byteCount)
    }

    /**
     * Write [byteCount] bytes starting at [arrayOffset] in [array] to [fileOffset].
     */
    fun write(fileOffset: Long, array: ByteArray, arrayOffset: Int = 0, byteCount: Int = array.size - arrayOffset) {
        checkOpen()
        checkWriteAllowed()
        checkBounds(array.size, arrayOffset, byteCount)
        require(fileOffset >= 0) { "fileOffset < 0" }
        protectedWrite(fileOffset, array, arrayOffset, byteCount)
    }

    protected abstract fun protectedClose()
    protected abstract fun protectedFlush()
    protected abstract fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int
    protected abstract fun protectedResize(size: Long)
    protected abstract fun protectedSize(): Long
    protected abstract fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int)

    private fun checkOpen() {
        lock.withLock {
            check(!closed) { "closed" }
        }
    }

    private fun checkWriteAllowed() {
        check(readWrite) { "Handle is not opened for write" }
    }

    private fun checkBounds(size: Int, offset: Int, count: Int) {
        require(offset >= 0) { "offset < 0" }
        require(count >= 0) { "byteCount < 0" }
        require(offset <= size) { "offset > size" }
        require(size - offset >= count) { "size - offset < byteCount" }
    }

    private fun readNoCloseCheck(fileOffset: Long, sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }

        var currentOffset = fileOffset
        val targetOffset = fileOffset + byteCount
        var sawAny = false

        while (currentOffset < targetOffset) {
            val writtenThisRound =
                UnsafeBufferOperations.writeToTail(sink, 1) { data: ByteArray, offset: Int, length: Int ->
                    val toRead = minOf((targetOffset - currentOffset).toInt(), length)
                    if (toRead <= 0) return@writeToTail 0
                    val rc = protectedRead(currentOffset, data, offset, toRead)
                    if (rc == -1) 0 else rc
                }
            if (writtenThisRound <= 0) {
                if (!sawAny) return -1L
                break
            }
            sawAny = true
            currentOffset += writtenThisRound
        }

        return currentOffset - fileOffset
    }

    private fun writeNoCloseCheck(fileOffset: Long, source: Buffer, byteCount: Long) {
        checkOffsetAndCount(source.size, 0L, byteCount)

        var currentOffset = fileOffset
        val targetOffset = fileOffset + byteCount

        while (currentOffset < targetOffset) {
            val consumed = UnsafeBufferOperations.readFromHead(source) { data, offset, length ->
                val toWrite = minOf((targetOffset - currentOffset).toInt(), length)
                if (toWrite <= 0) return@readFromHead 0
                protectedWrite(currentOffset, data, offset, toWrite)
                toWrite
            }
            if (consumed <= 0) break
            currentOffset += consumed
        }
    }

    private class FileHandleSource(
        val fileHandle: KFileHandle,
        var position: Long,
    ) : RawSource {
        var closed = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            val result = fileHandle.readNoCloseCheck(position, sink, byteCount)
            if (result != -1L) position += result
            return result
        }

        override fun close() {
            if (closed) return
            closed = true
            fileHandle.lock.withLock {
                fileHandle.openStreamCount--
                if (fileHandle.openStreamCount != 0 || !fileHandle.closed) return@close
            }
            fileHandle.protectedClose()
        }
    }

    private class FileHandleSink(
        val fileHandle: KFileHandle,
        var position: Long,
    ) : RawSink {
        var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "closed" }
            fileHandle.writeNoCloseCheck(position, source, byteCount)
            position += byteCount
        }

        override fun flush() {
            check(!closed) { "closed" }
            fileHandle.protectedFlush()
        }

        override fun close() {
            if (closed) return
            closed = true
            fileHandle.lock.withLock {
                fileHandle.openStreamCount--
                if (fileHandle.openStreamCount != 0 || !fileHandle.closed) return@close
            }
            fileHandle.protectedClose()
        }
    }
}

private class ArrayIndexOutOfBoundsException(
    override val message: String?
) : Exception()

internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset or byteCount < 0 || offset > size || size - offset < byteCount) {
        throw ArrayIndexOutOfBoundsException("size=$size offset=$offset byteCount=$byteCount")
    }
}
