/*
 * Copyright 2019 Felix Treede
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ksubprocess.io

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import platform.posix.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

internal class UnixFileHandle(
    readWrite: Boolean,
    file: CPointer<FILE>
) : KFileHandle(readWrite) {

    private val ref = FileRef(file)

    // closes the stream if the handle becomes unreachable without being closed
    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(ref) { it.close() }

    private val file: CPointer<FILE>
        get() = ref.file

    override fun protectedSize(): Long {
        memScoped {
            val stat = alloc<stat>()
            if (fstat(fileno(file), stat.ptr) != 0) {
                throw PosixException.forErrno("fstat")
            }
            return stat.st_size
        }
    }

    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int
    ): Int {
        if (byteCount == 0) return 0
        val fd = fileno(file)
        val canSeek = lseek(fd, fileOffset, SEEK_SET) > -1
        return array.usePinned { pinned ->
            val target = pinned.addressOf(arrayOffset)
            if (canSeek) {
                val bytesRead = pread(fd, target, byteCount.convert(), fileOffset).toInt()
                if (bytesRead == -1) throw PosixException.forErrno("pread")
                if (bytesRead == 0) -1 else bytesRead
            } else {
                readPipe(fd, target, byteCount)
            }
        }
    }

    // Waits in short polls so a stop request is noticed while no data arrives.
    private fun readPipe(fd: Int, target: CPointer<ByteVar>, byteCount: Int): Int {
        while (true) {
            val stop = isStopRequested
            val ready = pollReadable(fd, if (stop) 0 else STOP_CHECK_INTERVAL_MS)
            if (ready > 0) break
            if (ready == -1 && errno != EINTR) throw PosixException.forErrno("poll")
            if (ready == 0 && stop) return -1
        }
        while (true) {
            val bytesRead = read(fd, target, byteCount.convert()).toInt()
            if (bytesRead > 0) return bytesRead
            if (bytesRead == 0) return -1
            if (errno != EINTR) throw PosixException.forErrno("read")
        }
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int
    ) {
        if (byteCount == 0) return
        val fd = fileno(file)
        val canSeek = lseek(fd, fileOffset, SEEK_SET) > -1
        array.usePinned { pinned ->
            var written = 0
            while (written < byteCount) {
                val source = pinned.addressOf(arrayOffset + written)
                val remaining = byteCount - written
                val result = if (canSeek) {
                    pwrite(fd, source, remaining.convert(), fileOffset + written).toInt()
                } else {
                    writePipe(fd, source, remaining)
                }
                if (result == -1) {
                    if (errno == EINTR) continue
                    throw PosixException.forErrno("write")
                }
                written += result
            }
        }
    }

    override fun protectedFlush() {
        if (fflush(file) != 0) {
            throw PosixException.forErrno("fflush")
        }
    }

    override fun protectedResize(size: Long) {
        if (ftruncate(fileno(file), size) == -1) {
            throw PosixException.forErrno("ftruncate")
        }
    }

    override fun protectedClose() {
        if (ref.close() != 0) {
            throw PosixException.forErrno("fclose")
        }
    }
}

private const val STOP_CHECK_INTERVAL_MS = 50

/** write() for pipes that fails with EPIPE instead of raising SIGPIPE when the reader is gone. */
internal expect fun writePipe(fd: Int, source: CPointer<ByteVar>, count: Int): Int

/** poll() for POLLIN on a single descriptor. */
internal expect fun pollReadable(fd: Int, timeoutMs: Int): Int

internal fun Int.toFileHandle(write: Boolean): KFileHandle? {
    if (this == -1) return null
    val file = fdopen(this, if (write) "w" else "r") ?: throw PosixException.forErrno("fdopen")
    return UnixFileHandle(write, file)
}

/** Stands in for the stdin pipe once it was closed because the process exited. */
internal fun brokenPipeSink(): Sink = object : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        throw PosixException.forErrno("write", EPIPE)
    }

    override fun flush() {}

    override fun close() {}
}.buffered()

private class FileRef(file: CPointer<FILE>) {
    private val ptr = atomic<CPointer<FILE>?>(file)

    val file: CPointer<FILE>
        get() = checkNotNull(ptr.value) { "closed" }

    fun close(): Int = ptr.getAndSet(null)?.let { fclose(it) } ?: 0
}
