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

import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import ksubprocess.close
import platform.windows.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

internal class WindowsFileHandle(
    readWrite: Boolean,
    file: HANDLE?
) : KFileHandle(readWrite) {

    private val ref = HandleRef(file)

    // closes the handle if it becomes unreachable without being closed
    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(ref) { it.close(ignoreErrors = true) }

    private val file: HANDLE?
        get() = ref.handle

    private val isPipe = GetFileType(file) == FILE_TYPE_PIPE.convert<DWORD>()

    override fun protectedSize(): Long {
        memScoped {
            val result = alloc<LARGE_INTEGER>()
            if (GetFileSizeEx(file, result.ptr) == 0) {
                throw WindowsException.fromLastError(functionName = "GetFileSizeEx")
            }
            return result.toLong()
        }
    }

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (byteCount == 0) return 0
        val toRead = if (isPipe) awaitPipeData(byteCount) else byteCount
        if (toRead <= 0) return -1
        val bytesRead = array.usePinned { pinned ->
            read(pinned.addressOf(arrayOffset), fileOffset, toRead).toInt()
        }
        if (bytesRead == 0) return -1
        return bytesRead
    }

    // Returns how many bytes can be read without blocking, or -1 at EOF or once a stop was requested.
    private fun awaitPipeData(byteCount: Int): Int {
        memScoped {
            val available = alloc<DWORDVar>()
            var pause = 1
            while (true) {
                if (PeekNamedPipe(file, null, 0u, null, available.ptr, null) == 0) {
                    val ec = GetLastError()
                    if (ec.toInt() == ERROR_BROKEN_PIPE) return -1
                    throw IOException(
                        "IO operation failed due to windows error",
                        WindowsException.fromLastError(ec, functionName = "PeekNamedPipe")
                    )
                }
                if (available.value > 0u) return minOf(byteCount.toUInt(), available.value).toInt()
                if (isStopRequested) return -1
                Sleep(pause.convert())
                pause = minOf(pause * 2, MAX_PIPE_PAUSE_MS)
            }
        }
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        val bytesWritten = if (array.isNotEmpty()) {
            array.usePinned { pinned ->
                write(pinned.addressOf(arrayOffset), fileOffset, byteCount).toInt()
            }
        } else {
            0
        }
        if (bytesWritten != byteCount) throw IOException("bytesWritten=$bytesWritten")
    }

    override fun protectedFlush() {
        if (FlushFileBuffers(file) == 0) {
            throw WindowsException.fromLastError(functionName = "FlushFileBuffers")
        }
    }

    override fun protectedResize(size: Long) {
        memScoped {
            val distanceToMoveHigh = alloc<IntVar>()
            distanceToMoveHigh.value = (size ushr 32).toInt()
            val movePointerResult = SetFilePointer(
                hFile = file,
                lDistanceToMove = size.toInt(),
                lpDistanceToMoveHigh = distanceToMoveHigh.ptr,
                dwMoveMethod = FILE_BEGIN.convert()
            )
            if (movePointerResult == 0U) {
                throw WindowsException.fromLastError(functionName = "SetFilePointer")
            }
            if (SetEndOfFile(file) == 0) {
                throw WindowsException.fromLastError(functionName = "SetEndOfFile")
            }
        }
    }

    override fun protectedClose() {
        ref.close()
    }

    private fun LARGE_INTEGER.toLong(): Long {
        return (HighPart.toLong() shl 32) + (LowPart.toLong() and 0xffffffffL)
    }

    private fun read(target: CPointer<ByteVar>, offset: Long, length: Int): ULong {
        return memScoped {
            val overlapped = alloc<_OVERLAPPED>()
            overlapped.Offset = offset.toUInt()
            overlapped.OffsetHigh = (offset ushr 32).toUInt()
            val result = ReadFile(
                hFile = file,
                lpBuffer = target,
                nNumberOfBytesToRead = length.convert(),
                lpNumberOfBytesRead = null,
                lpOverlapped = overlapped.ptr
            )

            val ec = GetLastError()
            if (result == 0 && ec.toInt() != ERROR_HANDLE_EOF) {
                // handle some errors in a special way
                when (ec.toInt()) {
                    ERROR_BROKEN_PIPE -> {
                        // pipe got closed, essentially an EOF
                        return@memScoped 0u
                    }
                }
                throw IOException(
                    "IO operation failed due to windows error",
                    WindowsException.fromLastError(ec, functionName = "ReadFile")
                )
            }
            overlapped.InternalHigh
        }
    }

    private fun write(target: CPointer<ByteVar>, offset: Long, length: Int): ULong {
        return memScoped {
            val overlapped = alloc<_OVERLAPPED>()
            overlapped.Offset = offset.toUInt()
            overlapped.OffsetHigh = (offset ushr 32).toUInt()
            val result = WriteFile(
                hFile = file,
                lpBuffer = target,
                nNumberOfBytesToWrite = length.convert(),
                lpNumberOfBytesWritten = null,
                lpOverlapped = overlapped.ptr
            )
            if (result == 0 && GetLastError().toInt() != ERROR_HANDLE_EOF) {
                throw IOException(
                    "IO operation failed due to windows error",
                    WindowsException.fromLastError(functionName = "WriteFile")
                )
            }
            overlapped.InternalHigh
        }
    }
}

private const val MAX_PIPE_PAUSE_MS = 50

private class HandleRef(handle: HANDLE?) {
    private val ref = AtomicReference(handle)

    val handle: HANDLE?
        get() = ref.load()

    fun close(ignoreErrors: Boolean = false) {
        ref.exchange(INVALID_HANDLE_VALUE).close(ignoreErrors)
    }
}

/** Stands in for the stdin pipe once it was closed because the process exited. */
internal fun brokenPipeSink(): Sink = object : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        throw IOException(
            "IO operation failed due to windows error",
            WindowsException(ERROR_NO_DATA.convert(), "The pipe is being closed.")
        )
    }

    override fun flush() {}

    override fun close() {}
}.buffered()
