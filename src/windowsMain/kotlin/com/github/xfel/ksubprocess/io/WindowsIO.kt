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
package com.github.xfel.ksubprocess.io

import kotlinx.cinterop.*
import kotlinx.io.core.ExperimentalIoApi
import kotlinx.io.core.IoBuffer
import kotlinx.io.errors.IOException
import platform.posix.SSIZE_MAX
import platform.posix.size_t
import platform.posix.ssize_t
import platform.windows.*

internal const val SZERO: ssize_t = 0
internal const val ZERO: size_t = 0u

@ExperimentalIoApi
fun read(handle: HANDLE?, buffer: IoBuffer): ssize_t {
    var bytesRead: ssize_t = 0

    buffer.writeDirect { pointer ->
        val size = minOf(
            DWORD.MAX_VALUE.toULong(),
            SSIZE_MAX.toULong(),
            buffer.writeRemaining.toULong()
        ).convert<DWORD>()

        val result = memScoped {
            val rVar = alloc<DWORDVar>()
            if (ReadFile(handle, pointer, size, rVar.ptr, null) == 0) {
                val ec = GetLastError()
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
            rVar.value
        }

        bytesRead = result.convert()

        // it is completely safe to convert since the returned value will be never greater than Int.MAX_VALUE
        // however the returned value could be -1 so clamp it
        result.convert<Int>().coerceAtLeast(0)
    }

    return bytesRead
}

@ExperimentalIoApi
fun write(handle: HANDLE?, buffer: IoBuffer): ssize_t {
    var written: ssize_t = 0

    buffer.readDirect { pointer ->

        val result = memScoped {
            val rVar = alloc<DWORDVar>()
            if (WriteFile(handle, pointer, buffer.readRemaining.convert(), rVar.ptr, null) == 0) {
                throw IOException(
                    "IO operation failed due to windows error",
                    WindowsException.fromLastError(functionName = "WriteFile")
                )
            }
            rVar.value
        }

        written = result.convert()

        // it is completely safe to convert since the returned value will be never greater than Int.MAX_VALUE
        // however the returned value could be -1 so clamp it
        result.convert<Int>().coerceAtLeast(0)
    }

    return written
}
