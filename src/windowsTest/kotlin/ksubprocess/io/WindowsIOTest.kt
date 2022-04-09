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

import io.ktor.utils.io.charsets.Charsets.UTF_8
import io.ktor.utils.io.core.*
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Tests for the HANDLE-based IO functions.
 */
class WindowsIOTest {

    // TODO add more tests. Pipes specifically, and writing too.
    @Test
    fun `Reading a file`() {
        // open file handle
        val fd = CreateFileW(
            "testfiles/TestInput.txt",
            GENERIC_READ,
            0,
            null,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_READONLY,
            null
        )
        if (fd == INVALID_HANDLE_VALUE) {
            fail(
                "Error opening input file: " +
                    WindowsException.fromLastError(functionName = "CreateFileW").message
            )
        }

        val stream = Input(fd)
        try {
            val text = stream.readText(UTF_8)
            val expected = """
                Line1
                Line2
                
            """.trimIndent()

            for ((ex, act) in expected.lines() zip text.lines()) {
                assertEquals(ex, act)
            }
        } finally {
            stream.close()
        }
    }

    @Test
    fun `Reading+writing a pipe`() {
        val (readPipe, writePipe) = memScoped {
            // open a pipe
            val hReadPipe = alloc<HANDLEVar>()
            val hWritePipe = alloc<HANDLEVar>()

            if (CreatePipe(hReadPipe.ptr, hWritePipe.ptr, null, 0u) == 0) {
                fail(
                    "Error creating pipe" +
                        WindowsException.fromLastError(functionName = "CreateFileW").message
                )
            }

            hReadPipe.value to hWritePipe.value
        }

        val readStream = Input(readPipe)
        val writeStream = Output(writePipe)
        try {
            val text = "Hello World!"

            writeStream.writeText(text)
            writeStream.close()

            val afterPipe = readStream.readText(UTF_8)

            assertEquals(text, afterPipe)
        } finally {
            readStream.close()
            writeStream.close()
        }
    }
}
