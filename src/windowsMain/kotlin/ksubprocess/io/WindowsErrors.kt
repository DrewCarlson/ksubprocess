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

import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import platform.windows.*

private fun MAKELANGID(p: Int, s: Int) = ((s shl 10) or p).toUInt()

@OptIn(ExperimentalIoApi::class)
class WindowsException(val errorCode: DWORD, message: String) : Exception(message) {

    companion object {
        fun fromLastError(
            errorCode: DWORD = GetLastError(),
            functionName: String?
        ): WindowsException = memScoped {
            val msgBufHolder = alloc<LPWSTRVar>()

            FormatMessageW(
                (FORMAT_MESSAGE_ALLOCATE_BUFFER or
                    FORMAT_MESSAGE_FROM_SYSTEM or
                    FORMAT_MESSAGE_IGNORE_INSERTS).toUInt(),
                NULL,
                errorCode,
                MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
                interpretCPointer(msgBufHolder.rawPtr),
                0,
                null
            )

            val sysMsg = msgBufHolder.value?.toKString()

            val sysMsgWithCode =
                if (sysMsg != null) "error #${errorCode.toString(16)}: $sysMsg"
                else "error #${errorCode.toString(16)}"

            val message =
                if (functionName != null) "$functionName failed with $sysMsgWithCode"
                else sysMsgWithCode

            WindowsException(errorCode, message)
        }
    }
}
