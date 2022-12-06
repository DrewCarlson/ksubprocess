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

import kotlinx.coroutines.flow.Flow
import okio.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val POLLING_DELAY = 50.milliseconds

/**
 * A child process.
 *
 */
expect class Process
/**
 * Launch process using the specified arguments.
 *
 * @param args launch arguments
 * @throws ProcessException if the launch failed
 */
constructor(args: ProcessArguments) {

    /** Launch arguments used to start this process. */
    val args: ProcessArguments

    /** Check if the process is still running. */
    val isAlive: Boolean

    /** Exit code of terminated process, or `null` if the process is still running. */
    val exitCode: Int?

    /**
     * Wait for the process to terminate.
     * @return exit code
     */
    suspend fun waitFor(): Int

    /**
     * Wait for the process to terminate, using a timeout.
     *
     * @param timeout wait timeout duration
     * @return exit code or null if the process is still running
     */
    suspend fun waitFor(timeout: Duration): Int?

    /** stdin pipe if requested. */
    val stdin: BufferedSink?

    /** stdout pipe if requested. */
    val stdout: BufferedSource?

    /** stderr pipe if requested. */
    val stderr: BufferedSource?

    /** stdout lines if requested. */
    val stdoutLines: Flow<String>

    /** stderr lines if requested. */
    val stderrLines: Flow<String>

    /**
     * Terminate the child process.
     *
     * This method attempts to do so gracefully if the operating system is capable of doing so.
     */
    fun terminate()

    /**
     * Kill the child process.
     *
     * This method attempts to do so forcefully if the operating system is capable of doing so.
     */
    fun kill()

    /**
     * Close stdin handles and free resources so allow process to complete.
     */
    fun closeStdin()
}

/**
 * Launch process using builder.
 *
 * @param builder builder callback
 * @throws ProcessException if the launch failed
 */
inline fun Process(builder: ProcessArgumentBuilder.() -> Unit) =
    Process(ProcessArguments(builder))
