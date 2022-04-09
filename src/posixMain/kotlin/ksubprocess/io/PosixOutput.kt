/*
 * Copyright 2022 Drew Carlson
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

import io.ktor.utils.io.bits.Memory
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Create a blocking [Output] writing to the specified [fileDescriptor] using [write].
 */
fun Output(fileDescriptor: Int): Output = PosixFileDescriptorOutput(fileDescriptor)

private class PosixFileDescriptorOutput(val fileDescriptor: Int) : Output() {
    private var closed = false

    init {
        check(fileDescriptor >= 0) { "Illegal fileDescriptor: $fileDescriptor" }
    }

    override fun flush(source: Memory, offset: Int, length: Int) {
        val fileDescriptor = fileDescriptor
        val end = offset + length
        var currentOffset = offset

        while (currentOffset < end) {
            val result = write(fileDescriptor, source, currentOffset, end - currentOffset)
            if (result == 0) {
                throw IOException(
                    "I/O operation failed due to posix error code $errno",
                    PosixException.forErrno(posixFunctionName = "fwrite()")
                )
            }
            currentOffset += result
        }
    }

    override fun closeDestination() {
        if (closed) return
        closed = true

        if (close(fileDescriptor) != 0) {
            val error = errno
            if (error != EBADF) { // EBADF is already closed or not opened
                throw IOException(
                    "I/O operation failed due to posix error code $errno",
                    PosixException.forErrno(posixFunctionName = "close()")
                )
            }
        }
    }
}
