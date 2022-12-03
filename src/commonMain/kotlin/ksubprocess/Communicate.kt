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

import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.time.*

/**
 * Result tuple of Process.communicate().
 *
 * @param exitCode process exit code. Is 0 if the process terminated normally
 * @param output stdout pipe output, or empty if stdout wasn't a pipe
 * @param errors stderr pipe output, or empty if stderr wasn't a pipe
 */
data class CommunicateResult(
    val exitCode: Int,
    val output: String,
    val errors: String
) {

    /**
     * Check that the process exited normally, ie with [exitCode] 0. If not, throw [ProcessExitException].
     *
     * @throws ProcessExitException if `exitCode != 0`
     */
    fun check() {
        if (exitCode != 0) throw ProcessExitException(this)
    }
}

/**
 * Communicate with the process and wait for its termination.
 *
 * If stdin is a pipe, input will be written to it. Afterwards, stdin will be closed to signal end-of-input.
 *
 * If stdout or stderr are pipes, their output will be collected and returned on completion. The pipes will be closed
 * on termination. The pipe collection runs in background threads to avoid buffer overflows in the pipe.
 *
 * If a timeout is set, the child will be [terminated][Process.terminate] if it doesn't finish soon enough. An extra
 * timeout for graceful termination can be set, afterwards the child will be [killed][Process.kill]. The kill timeout
 * can also be set to [Duration.ZERO] to skip the graceful termination attempt and kill the child directly.
 *
 *
 * @param input stdin pipe input. Ignored if stdin isn't a pipe
 * @param charset charset to use for text communication. Defaults to UTF-8
 * @param timeout timeout for child process if desired
 * @param killTimeout extra timeout before the terminated child is killed. May be ZERO to kill directly
 *
 * @return result of communication
 *
 * @throws ProcessException if another process error occurs
 * @throws io.ktor.utils.io.errors.IOException if an IO error occurs in the pipes
 */
suspend fun Process.communicate(
    input: String = "",
    charset: Charset = Charsets.UTF_8,
    timeout: Duration? = null,
    killTimeout: Duration? = null
): CommunicateResult = coroutineScope {
    // start output pipe collectors
    val stdoutCollector =
        if (args.stdout == Redirect.Pipe) async { requireNotNull(stdout).readText(charset) }
        else null
    val stderrCollector =
        if (args.stderr == Redirect.Pipe) async { requireNotNull(stderr).readText(charset) }
        else null

    // push out the input data
    stdin?.let {
        it.writeText(input, charset = charset)
        // close input stream to notify child of input end
        it.close()
    }

    // wait with timeout if needed
    if (timeout != null && waitFor(timeout) == null) {
        // didn't exit in timeout, so terminate explicitly
        if (killTimeout == Duration.ZERO) {
            // kill directly
            kill()
        } else {
            // try gently first
            terminate()

            // wait a little more and kill if requested
            if (killTimeout != null && waitFor(killTimeout) == null) {
                kill()
            }
        }
    }

    // wait for the process to actually die
    val exitCode = waitFor()

    // wait for output collectors
    val results = listOfNotNull(stdoutCollector, stderrCollector).awaitAll()
    val output = results.firstOrNull().orEmpty()
    val error = results.lastOrNull().orEmpty()

    // return result
    CommunicateResult(exitCode, output, error)
}
