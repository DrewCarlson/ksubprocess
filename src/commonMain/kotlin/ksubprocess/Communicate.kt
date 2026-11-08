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

import kotlinx.coroutines.*
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.*

/**
 * Result tuple of Process.communicate().
 *
 * @param exitCode process exit code. Is 0 if the process terminated normally, see [Process.exitCode]
 * @param output stdout pipe output, or empty if stdout wasn't a pipe
 * @param errors stderr pipe output, or empty if stderr wasn't a pipe
 */
public data class CommunicateResult(
    val exitCode: Int,
    val output: String,
    val errors: String
) {

    /**
     * Check that the process exited normally, ie with [exitCode] 0. If not, throw [ProcessExitException].
     *
     * @throws ProcessExitException if `exitCode != 0`
     */
    public fun check() {
        if (exitCode != 0) throw ProcessExitException(this)
    }
}

/**
 * Communicate with the process and wait for its termination.
 *
 * If stdin is a pipe, input will be written to it. Afterwards, stdin will be closed to signal end-of-input.
 *
 * If stdout or stderr are pipes, their output will be collected and returned on completion. The pipes will be closed
 * on termination. The pipe collection runs in background threads to avoid buffer overflows in the pipe. Collection
 * ends when the process exits: output written later by processes it left running in the background is not waited for.
 *
 * If the process exits or closes stdin before reading all of [input], the rest of the input is discarded.
 *
 * If a timeout is set, the child will be [terminated][Process.terminate] if it doesn't finish soon enough. An extra
 * timeout for graceful termination can be set, afterwards the child will be [killed][Process.kill]. The kill timeout
 * can also be set to [Duration.ZERO] to skip the graceful termination attempt and kill the child directly.
 *
 * If [killTimeout] is null, a timed out child is only terminated and this call waits for it to exit.
 *
 * If the calling coroutine is cancelled or an error occurs, the child is killed and reaped before the exception is
 * rethrown.
 *
 * @param input stdin pipe input. Ignored if stdin isn't a pipe
 * @param timeout timeout for child process if desired
 * @param killTimeout extra timeout before the terminated child is killed. May be ZERO to kill directly
 *
 * @return result of communication
 *
 * @throws ProcessException if another process error occurs
 * @throws kotlinx.io.IOException if an IO error occurs in the pipes
 */
public suspend fun Process.communicate(
    input: String = "",
    timeout: Duration? = null,
    killTimeout: Duration? = null
): CommunicateResult {
    val stdoutSource = if (args.stdout == Redirect.Pipe) requireNotNull(stdout) else null
    val stderrSource = if (args.stderr == Redirect.Pipe) requireNotNull(stderr) else null
    try {
        return coroutineScope {
            // reads block, so keep them off the caller's dispatcher
            val stdoutCollector = stdoutSource?.let { async(Dispatchers.IO) { it.readString() } }
            val stderrCollector = stderrSource?.let { async(Dispatchers.IO) { it.readString() } }

            try {
                // push out the input data
                try {
                    stdin?.writeString(input)
                    closeStdin()
                } catch (e: Exception) {
                    // the child exited or closed stdin before reading all input
                    if (!e.isBrokenPipe()) throw e
                    runCatching { closeStdin() }
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

                // everything the child wrote is in the pipes now; don't wait for processes it left running
                drainOutput()

                CommunicateResult(
                    exitCode,
                    stdoutCollector?.await().orEmpty(),
                    stderrCollector?.await().orEmpty()
                )
            } catch (e: Throwable) {
                // don't leave a running child or a zombie behind; the collectors finish once it is gone
                withContext(NonCancellable) {
                    runCatching { closeStdin() }
                    if (exitCode == null) {
                        runCatching { kill() }
                        waitFor()
                    }
                    drainOutput()
                }
                throw e
            }
        }
    } finally {
        stdoutSource?.close()
        stderrSource?.close()
    }
}
