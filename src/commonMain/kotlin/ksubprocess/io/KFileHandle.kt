package ksubprocess.io

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.buffered
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt

/**
 * Minimal internal cross-platform file/pipe handle abstraction.
 */
@OptIn(UnsafeIoApi::class)
internal abstract class KFileHandle(
    private val readWrite: Boolean,
) : AutoCloseable {
    // bit 0: the handle was closed, remaining bits: number of open streams
    private val state = AtomicInt(0)

    @Volatile
    private var stopRequested = false

    /**
     * True once [stopAfterAvailable] was called. Implementations reading from pipes then return data that is
     * already available and report end of stream instead of waiting for more.
     */
    protected val isStopRequested: Boolean
        get() = stopRequested

    /**
     * Returns a source that reads from this starting at [fileOffset]. The returned source must be
     * closed when it is no longer needed.
     */
    @Throws(IOException::class)
    fun source(fileOffset: Long = 0L): Source {
        openStream(markClosed = false)
        return FileHandleSource(this, fileOffset).buffered()
    }


    @Throws(IOException::class)
    fun sink(fileOffset: Long = 0L): Sink {
        check(readWrite) { "file handle is read-only" }
        openStream(markClosed = false)
        return FileHandleSink(this, fileOffset).buffered()
    }

    /** Returns a source that takes ownership of this handle: closing the source closes the handle. */
    fun ownedSource(): Source = source().also { close() }

    /** Like [ownedSource] for writing, or null if the handle is already closed. */
    fun ownedSinkOrNull(): Sink? {
        check(readWrite) { "file handle is read-only" }
        if (!openStream(markClosed = true)) return null
        return FileHandleSink(this, 0L).buffered()
    }

    /** Stop waiting for data that is not available yet; see [isStopRequested]. */
    fun stopAfterAvailable() {
        stopRequested = true
    }

    /** Close this handle. It is safe to call multiple times. */
    override fun close() {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0) return
            if (state.compareAndSet(current, current or CLOSED)) {
                if (current == 0) protectedClose()
                return
            }
        }
    }

    // Registers a stream, optionally closing the handle at the same time. With markClosed it returns false
    // instead of failing if the handle is already closed.
    private fun openStream(markClosed: Boolean): Boolean {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0) {
                check(markClosed) { "closed" }
                return false
            }
            val next = (current + STREAM) or (if (markClosed) CLOSED else 0)
            if (state.compareAndSet(current, next)) return true
        }
    }

    // Closes the underlying handle once the last stream is gone from a closed handle.
    private fun releaseStream() {
        if (state.addAndFetch(-STREAM) == CLOSED) protectedClose()
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
        check(state.load() and CLOSED == 0) { "closed" }
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

    // A single read: returning what is available keeps pipe output flowing while the child runs.
    private fun readNoCloseCheck(fileOffset: Long, sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        var result = -1
        UnsafeBufferOperations.writeToTail(sink, 1) { data: ByteArray, startIndex: Int, endIndex: Int ->
            result = protectedRead(fileOffset, data, startIndex, minOf(byteCount, (endIndex - startIndex).toLong()).toInt())
            if (result == -1) 0 else result
        }
        return result.toLong()
    }

    private fun writeNoCloseCheck(fileOffset: Long, source: Buffer, byteCount: Long) {
        checkOffsetAndCount(source.size, 0L, byteCount)

        var currentOffset = fileOffset
        val targetOffset = fileOffset + byteCount

        while (currentOffset < targetOffset) {
            val consumed = UnsafeBufferOperations.readFromHead(source) { data, startIndex, endIndex ->
                val toWrite = minOf(targetOffset - currentOffset, (endIndex - startIndex).toLong()).toInt()
                if (toWrite <= 0) return@readFromHead 0
                protectedWrite(currentOffset, data, startIndex, toWrite)
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
        private var released = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            if (released) return -1L
            val result = fileHandle.readNoCloseCheck(position, sink, byteCount)
            if (result == -1L) {
                // Nothing can follow EOF on a pipe, so give the descriptor back right away.
                release()
            } else {
                position += result
            }
            return result
        }

        override fun close() {
            if (closed) return
            closed = true
            release()
        }

        private fun release() {
            if (released) return
            released = true
            fileHandle.releaseStream()
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
            fileHandle.releaseStream()
        }
    }
}

private const val CLOSED = 1
private const val STREAM = 2

private class ArrayIndexOutOfBoundsException(
    override val message: String?
) : Exception()

internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset or byteCount < 0 || offset > size || size - offset < byteCount) {
        throw ArrayIndexOutOfBoundsException("size=$size offset=$offset byteCount=$byteCount")
    }
}
