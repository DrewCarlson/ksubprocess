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
package drewcarlson.ksubprocess

import io.ktor.utils.io.core.*
import kotlin.time.Duration

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
    fun waitFor(): Int

    /**
     * Wait for the process to terminate, using a timeout.
     *
     * @param timeout wait timeout duration
     * @return exit code or null if the process is still running
     */
    fun waitFor(timeout: Duration): Int?

    // TODO some kind of coroutine support

    /** stdin pipe if requested. */
    val stdin: Output?

    /** stdout pipe if requested. */
    val stdout: Input?

    /** stderr pipe if requested. */
    val stderr: Input?

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

}

/**
 * Launch process using builder.
 *
 * @param builder builder callback
 * @throws ProcessException if the launch failed
 */
@Suppress("FunctionName")
inline fun Process(builder: ProcessArgumentBuilder.() -> Unit) =
    Process(ProcessArguments(builder))
