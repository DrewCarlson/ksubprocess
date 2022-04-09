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

import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.toKString
import platform.posix.__environ
import platform.posix.getenv

private data class EnvEntry(override val key: String, override val value: String) : Map.Entry<String, String>

@ThreadLocal
actual object Environment : AbstractMap<String, String>(), Map<String, String> {

    // fastpath get and contains through getenv
    override fun containsKey(key: String): Boolean = getenv(key) != null

    override fun get(key: String): String? = getenv(key)?.toKString()

    // also fastpath entries.contains
    override val entries: Set<Map.Entry<String, String>> = object : AbstractSet<Map.Entry<String, String>>() {
        override fun contains(element: Map.Entry<String, String>): Boolean = get(element.key) == element.value

        // only perform full scan if really needed
        // note: these are not thread safe, but that's a fault of the underlying API.
        override val size: Int
            get() {
                var sz = 0
                val ep = __environ
                // loop until null entry
                if (ep != null) {
                    while (ep[sz] != null) sz++
                }
                return sz
            }

        override fun iterator() = object : Iterator<Map.Entry<String, String>> {

            var ep = __environ

            override fun hasNext(): Boolean {
                return ep?.get(0) != null
            }

            override fun next(): Map.Entry<String, String> {
                // get current element as kstring
                val cur = ep?.get(0)?.toKString() ?: throw NoSuchElementException()

                // separate key/value
                val (key, value) = cur.split('=', limit = 2)
                // increment
                ep += 1
                return EnvEntry(key, value)
            }
        }
    }

    actual val caseInsensitive: Boolean
        get() = false // never on linux
}

internal fun Map<String, String>.toEnviron() = map { "${it.key}=${it.value}" }
