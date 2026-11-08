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
        val canSeek = lseek(fileno(file), fileOffset, SEEK_SET) > -1
        val bytesRead = if (array.isNotEmpty()) {
            array.usePinned { pinned ->
                if (canSeek) {
                    pread(fileno(file), pinned.addressOf(arrayOffset), byteCount.convert(), fileOffset)
                } else {
                    read(fileno(file), pinned.addressOf(arrayOffset), byteCount.convert())
                }
            }.convert<Int>()
        } else {
            0
        }
        if (bytesRead == -1) throw PosixException.forErrno("pread")
        if (bytesRead == 0) return -1
        return bytesRead
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int
    ) {
        val canSeek = lseek(fileno(file), fileOffset, SEEK_SET) > -1
        val bytesWritten = if (array.isNotEmpty()) {
            array.usePinned { pinned ->
                if (canSeek) {
                    pwrite(fileno(file), pinned.addressOf(arrayOffset), byteCount.convert(), fileOffset)
                } else {
                    write(fileno(file), pinned.addressOf(arrayOffset), byteCount.convert())
                }
            }.convert<Int>()
        } else {
            0
        }
        if (bytesWritten != byteCount) throw PosixException.forErrno("pwrite")
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

private class FileRef(file: CPointer<FILE>) {
    private val ptr = atomic<CPointer<FILE>?>(file)

    val file: CPointer<FILE>
        get() = checkNotNull(ptr.value) { "closed" }

    fun close(): Int = ptr.getAndSet(null)?.let { fclose(it) } ?: 0
}
