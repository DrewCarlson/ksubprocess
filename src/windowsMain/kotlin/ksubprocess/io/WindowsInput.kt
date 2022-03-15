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

import ksubprocess.close
import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*
import platform.windows.HANDLE

@Suppress("FunctionName")
@OptIn(ExperimentalIoApi::class)
fun Input(handle: HANDLE?): Input = WindowsInputForFileHandle(handle)

@OptIn(ExperimentalIoApi::class)
private class WindowsInputForFileHandle(val handle: HANDLE?) : AbstractInput() {
    private var closed = false
    override fun closeSource() {
        if (closed) return
        closed = true
        handle.close()
    }

    override fun fill(destination: Memory, offset: Int, length: Int): Int {
        return read(handle, destination, length).toInt()
    }
}
