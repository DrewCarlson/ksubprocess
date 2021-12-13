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
package com.github.xfel.ksubprocess

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the given stream's text in the background, used by communicate.
 *
 * The JVM version is simply backed by a thread.
 */
internal actual class BackgroundPipeCollector actual constructor(
    process: Process,
    isStderr: Boolean,
    private val charset: Charset
) : Thread("background-stream-reader-${counter.getAndIncrement()}") {

    actual companion object {
        @JvmStatic
        private val counter = AtomicInteger()

        @JvmStatic
        actual fun awaitAll(readers: List<BackgroundPipeCollector>) = readers.forEach { it.await() }
    }

    private val stream = (if (isStderr) process.stderr else process.stdout)
        ?: throw IllegalArgumentException("BackgroundStreamReader requested for non-pipe stream.")

    private val buffer = StringBuffer()

    init {
        // run as daemon
        isDaemon = true
        // start the thread
        start()
    }

    override fun run() {
        try {
            // read into buffer
            stream.readText(buffer, charset)
        } finally {
            stream.close()
        }
    }

    actual fun await() {
        // wait for termination
        join()
    }

    actual val result: String
        get() = buffer.toString()


}