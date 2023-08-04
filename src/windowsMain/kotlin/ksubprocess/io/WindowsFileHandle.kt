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
import okio.FileHandle
import okio.IOException
import platform.windows.*

internal class WindowsFileHandle(
    readWrite: Boolean,
    private val file: HANDLE?
) : FileHandle(readWrite) {
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
        val bytesRead = array.usePinned { pinned ->
            read(pinned.addressOf(arrayOffset), fileOffset, byteCount).toInt()
        }
        if (bytesRead == 0) return -1
        return bytesRead
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        val bytesWritten = array.usePinned { pinned ->
            write(pinned.addressOf(arrayOffset), fileOffset, byteCount).toInt()
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
        if (CloseHandle(file) == 0) {
            throw WindowsException.fromLastError(functionName = "CloseHandle")
        }
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
