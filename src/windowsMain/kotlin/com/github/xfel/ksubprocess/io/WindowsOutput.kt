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

import com.github.xfel.ksubprocess.close
import kotlinx.io.core.AbstractOutput
import kotlinx.io.core.ExperimentalIoApi
import kotlinx.io.core.IoBuffer
import kotlinx.io.core.Output
import platform.windows.HANDLE

@Suppress("FunctionName")
@ExperimentalIoApi
fun Output(handle: HANDLE?): Output = WindowsOutputForFileHandle(handle)

@ExperimentalIoApi
private class WindowsOutputForFileHandle(val handle: HANDLE?) : AbstractOutput() {
    private var closed = false
    override fun closeDestination() {
        if (closed) return
        closed = true
        handle.close()
    }

    override fun flush(buffer: IoBuffer) {
        while (buffer.canRead()) {
            write(handle, buffer)
        }
    }

}