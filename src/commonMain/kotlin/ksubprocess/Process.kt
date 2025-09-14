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
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val POLLING_DELAY = 50.milliseconds

/**
 * A child process.
 *
 */
public expect class Process
/**
 * Launch process using the specified arguments.
 *
 * @param args launch arguments
 * @throws ProcessException if the launch failed
 */
constructor(args: ProcessArguments) {

    /** Launch arguments used to start this process. */
    public val args: ProcessArguments

    /** Check if the process is still running. */
    public val isAlive: Boolean

    /** Exit code of terminated process, or `null` if the process is still running. */
    public val exitCode: Int?

    /**
     * Wait for the process to terminate.
     * @return exit code
     */
    public suspend fun waitFor(): Int

    /**
     * Wait for the process to terminate, using a timeout.
     *
     * @param timeout wait timeout duration
     * @return exit code or null if the process is still running
     */
    public suspend fun waitFor(timeout: Duration): Int?

    /** stdin pipe if requested. */
    public val stdin: Sink?

    /** stdout pipe if requested. */
    public val stdout: Source?

    /** stderr pipe if requested. */
    public val stderr: Source?

    /** stdout lines if requested. */
    public val stdoutLines: Flow<String>

    /** stderr lines if requested. */
    public val stderrLines: Flow<String>

    /**
     * Terminate the child process.
     *
     * This method attempts to do so gracefully if the operating system is capable of doing so.
     */
    public fun terminate()

    /**
     * Kill the child process.
     *
     * This method attempts to do so forcefully if the operating system is capable of doing so.
     */
    public fun kill()

    /**
     * Close stdin handles and free resources to allow process to complete.
     */
    public fun closeStdin()
}

/**
 * Launch process using builder.
 *
 * @param builder builder callback
 * @throws ProcessException if the launch failed
 */
public inline fun Process(builder: ProcessArgumentBuilder.() -> Unit): Process =
    Process(ProcessArguments(builder))
