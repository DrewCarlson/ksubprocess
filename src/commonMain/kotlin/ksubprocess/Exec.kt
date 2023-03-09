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

import kotlin.time.*

/**
 * Builder for the [exec] method.
 *
 * Inherits from [ProcessArgumentBuilder] to define launch settings.
 */
public class ExecArgumentsBuilder : ProcessArgumentBuilder() {
    /**
     * stdin pipe input. Ignored if stdin isn't a pipe.
     *
     * @see communicate
     */
    public var input: String = ""

    /**
     * Timeout for the child process. Set to null to run without timeout.
     *
     * If the child process doesn't exit within the timeout, it will be [terminated][Process.terminate].
     *
     * @see communicate
     */
    public var timeout: Duration? = null
        set(value) {
            require(value == null || value.isPositive()) { "Timeout must be positive!" }
            field =
                if (value?.isInfinite() == true) null
                else value
        }

    /**
     * If timeout triggers, an optional additional wait time before the child process is [killed][Process.kill].
     *
     * Can be set to zero to kill the child directly, without trying to terminate it.
     *
     * @see communicate
     */
    public var killTimeout: Duration? = null
        set(value) {
            require(value == null || (value.isPositive() || value == Duration.ZERO)) {
                "Kill timeout must be positive or zero!"
            }
            field =
                if (value?.isInfinite() == true) null
                else value
        }

    /**
     * Set to true to throw a [ProcessExitException] if the process doesn't terminate normally.
     *
     * @see CommunicateResult.check
     */
    public var check: Boolean = false

    // convenience builder functions
    /**
     * Fill [input] using a string builder.
     */
    public inline fun input(builder: StringBuilder.() -> Unit) {
        input = buildString(builder)
    }
}

/**
 * Launch a process, communicate with it and check the result.
 *
 * Delegates to [Process], [communicate] and [CommunicateResult.check] as needed.
 *
 * @param builder builder for method arguments
 * @return result of communicate
 * @throws ProcessExitException if `check = true` and the process didn't terminate normally.
 * @throws ProcessException if another process error occurs
 * @throws io.ktor.utils.io.errors.IOException if an IO error occurs in the pipes
 */
public suspend fun exec(builder: ExecArgumentsBuilder.() -> Unit): CommunicateResult {
    val rab = ExecArgumentsBuilder()
    rab.builder()

    // start process
    val proc = Process(rab.build())

    // communicate with it
    val res = proc.communicate(
        rab.input,
        rab.timeout,
        rab.killTimeout
    )

    // check if requested
    if (rab.check) {
        res.check()
    }

    // return result
    return res
}
