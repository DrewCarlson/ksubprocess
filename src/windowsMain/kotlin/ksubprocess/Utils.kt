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
package ksubprocess

import kotlinx.cinterop.convert
import ksubprocess.io.WindowsException
import platform.windows.*

// close a windows handle and optionally report errors
internal fun HANDLE?.close(ignoreErrors: Boolean = false) {
    if (this == INVALID_HANDLE_VALUE) return
    if (CloseHandle(this) == 0 && !ignoreErrors) {
        val ec = GetLastError()
        if (ec.convert<Int>() != ERROR_INVALID_HANDLE) {
            throw ProcessException(
                "Error closing handle",
                WindowsException.fromLastError(ec, "CloseHandle")
            )
        }
    }
}
