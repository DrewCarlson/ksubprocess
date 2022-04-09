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

import io.ktor.utils.io.bits.*
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import platform.posix.*

internal fun write(fildes: Int, source: Memory, offset: Int, length: Int): Int {
    return write(fildes, source, offset.toLong(), length.toLong()).toInt()
}

internal fun write(fildes: Int, source: Memory, offset: Long, length: Long): Long {
    val maxLength = minOf<Long>(length, ssize_t.MAX_VALUE.convert())
    return write(fildes, source.pointer + offset, maxLength.convert()).convert()
}

internal fun read(fildes: Int, destination: Memory, offset: Int, length: Int): Int {
    return read(fildes, destination, offset.toLong(), length.toLong()).toInt()
}

internal fun read(fildes: Int, destination: Memory, offset: Long, length: Long): Long {
    val maxLength = minOf<Long>(
        ssize_t.MAX_VALUE.convert(),
        length
    )

    return read(fildes, destination.pointer + offset, maxLength.convert()).convert<Long>().coerceAtLeast(0)
}
