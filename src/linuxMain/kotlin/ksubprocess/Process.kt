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
@file:OptIn(ExperimentalIoApi::class)

package ksubprocess

import ksubprocess.iop.fork_and_run
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.Input
import io.ktor.utils.io.errors.*
import io.ktor.utils.io.streams.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.native.concurrent.freeze
import kotlin.time.*

// safely close an fd
private fun Int.closeFd() {
    if (this != -1) {
        close(this)
    }
}

// read and write fds for a pipe. Also used to store other fds for convenience.
private data class RedirectFds(val readFd: Int, val writeFd: Int) {
    constructor(fd: Int, isRead: Boolean) : this(
        if (isRead) fd else -1,
        if (isRead) -1 else fd
    )
}


private fun Redirect.openFds(stream: String): RedirectFds = when (this) {
    Redirect.Null -> {
        val fd = open("/dev/null", O_RDWR)
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening null file for $stream",
                PosixException.forErrno(posixFunctionName = "open()")
            )
        }
        RedirectFds(fd, stream == "stdin")
    }
    Redirect.Inherit -> RedirectFds(-1, -1)
    Redirect.Pipe -> {
        val fds = IntArray(2)
        val piperes = fds.usePinned {
            pipe(it.addressOf(0))
        }
        if (piperes == -1) {
            throw ProcessConfigException(
                "Error opening $stream pipe",
                PosixException.forErrno(posixFunctionName = "pipe()")
            )
        }
        RedirectFds(fds[0], fds[1])
    }
    is Redirect.Read -> {
        val fd = open(file, O_RDONLY)
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening input file $file for $stream",
                PosixException.forErrno(posixFunctionName = "open()")
            )
        }
        RedirectFds(fd, -1)
    }
    is Redirect.Write -> {
        val fd = open(
            file,
            if (append) O_WRONLY or O_APPEND
            else O_WRONLY
        )
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening output file $file for $stream",
                PosixException.forErrno(posixFunctionName = "open()")
            )
        }
        RedirectFds(-1, fd)
    }
    Redirect.Stdout -> throw IllegalStateException("Redirect.Stdout must be handled separately.")
}


private fun MemScope.toCStrVector(data: List<String>): CArrayPointer<CPointerVar<ByteVar>> {
    val res = allocArray<CPointerVar<ByteVar>>(data.size + 1)
    for (i in data.indices) {
        res[i] = data[i].cstr.ptr
    }
    res[data.size] = null
    return res
}


actual class Process actual constructor(
    actual val args: ProcessArguments
) {

    // set to true when done
    private var terminated = false
    // exit status - only valid once terminated = true
    private var _exitStatus = -1

    private val childPid: pid_t

    // file descriptors for child pipes
    internal val stdoutFd: Int
    internal val stderrFd: Int
    private val stdinFd: Int

    init {
        // find executable
        var executable = args.arguments[0]

        if ('/' !in executable) {
            // locate on path
            executable = findExecutable(executable)
                ?: throw ProcessConfigException("Unable to find executable '$executable' on PATH")
        }

        // verify working directory
        args.workingDirectory?.let {
            // try to open it, that's generally enough
            val dir = opendir(it) ?: throw ProcessConfigException("Working directory '$it' cannot be used!")
            closedir(dir)
        }

        // init redirects/pipes
        var stdout: RedirectFds? = null
        var stderr: RedirectFds? = null
        var stdin: RedirectFds? = null
        try {
            // init redirects
            stdout = args.stdout.openFds("stdout")
            stderr = if (args.stderr == Redirect.Stdout)
            // use stdout
                RedirectFds(-1, stdout.writeFd)
            else
                args.stderr.openFds("stderr")
            stdin = args.stdin.openFds("stdin")

            val pid = memScoped {
                // convert c lists
                val arguments = toCStrVector(args.arguments)
                val env = args.environment?.let { toCStrVector(it.map { e -> "${e.key}=${e.value}" }) }

                fork_and_run(
                    executable,
                    arguments,
                    args.workingDirectory,
                    env,
                    stdout.writeFd,
                    stderr.writeFd,
                    stdin.readFd,
                    stdout.readFd,
                    stderr.readFd,
                    stdin.writeFd
                )
            }
            if (pid == -1) {
                // fork failed
                throw ProcessException(
                    "Error staring subprocess",
                    PosixException.forErrno(posixFunctionName = "fork()")
                )
            }
            childPid = pid

            // store file descriptors
            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            // close unused fds (don't need to watch stderr=stdout here)
            stdout.writeFd.closeFd()
            stderr.writeFd.closeFd()
            stdin.readFd.closeFd()
        } catch (t: Throwable) {
            // close fds on error
            stdout?.readFd?.closeFd()
            stdout?.writeFd?.closeFd()
            if (args.stderr != Redirect.Stdout) {
                stderr?.readFd?.closeFd()
                stderr?.writeFd?.closeFd()
            }
            stdin?.readFd?.closeFd()
            stdin?.writeFd?.closeFd()
            throw t
        }

        if (Platform.memoryModel == MemoryModel.STRICT) {
            args.freeze() // frozen args val since they get shared.
        }
    }

    private fun cleanup() {
        // close fds as needed. Errors are ignored.
        stdoutFd.closeFd()
        stderrFd.closeFd()
        stdinFd.closeFd()
    }

    private fun checkState(block: Boolean = false) {
        if (terminated) return
        var options = 0
        if (!block) {
            options = options or WNOHANG
        }
        memScoped {
            val info = alloc<siginfo_t>()
            val res = waitid(idtype_t.P_PID, childPid.convert(), info.ptr, options or WEXITED)
            if (res == -1) {
                // an error
                throw ProcessException(
                    "Error querying process state",
                    PosixException.forErrno(posixFunctionName = "waitpid()")
                )
            }
            when (info.si_code) {
                CLD_EXITED, CLD_KILLED, CLD_DUMPED -> {
                    // process has terminated
                    terminated = true
                    _exitStatus = info._sifields._sigchld.si_status
                    cleanup()
                }
            }
            // else we are not done
        }
    }

    actual val isAlive: Boolean
        get() {
            checkState()
            return !terminated
        }
    actual val exitCode: Int?
        get() {
            checkState()
            return if (terminated) _exitStatus else null
        }

    actual fun waitFor(): Int {
        while (!terminated) {
            checkState(true)
        }
        return _exitStatus
    }

    @OptIn(ExperimentalTime::class)
    actual fun waitFor(timeout: Duration): Int? {
        require(timeout.isPositive()) { "Timeout must be positive!" }
        // there is no good blocking solution, so use an active loop with sleep in between.
        val clk = TimeSource.Monotonic
        val deadline = clk.markNow() + timeout
        while (true) {
            checkState(false)
            // return if done or now passed the deadline
            if (terminated) return _exitStatus
            if (deadline.hasPassedNow()) return null
            // TODO select good frequency
            memScoped {
                val ts = alloc<timespec>()
                ts.tv_nsec = 50 * 1000
                nanosleep(ts.ptr, ts.ptr)
            }
        }
    }

    actual fun terminate() {
        sendSignal(SIGTERM)
    }

    actual fun kill() {
        sendSignal(SIGKILL)
    }

    /**
     * Send the given signal to the child process.
     *
     * @param signal signal number
     */
    fun sendSignal(signal: Int) {
        if (terminated) return
        if (kill(childPid, signal) != 0) {
            throw ProcessException(
                "Error terminating process",
                PosixException.forErrno(posixFunctionName = "kill()")
            )
        }
    }

    actual val stdin: Output? by lazy {
        if (stdinFd != -1) Output(stdinFd)
        else null
    }
    actual val stdout: Input? by lazy {
        if (stdoutFd != -1) Input(stdoutFd)
        else null
    }
    actual val stderr: Input? by lazy {
        if (stderrFd != -1) Input(stderrFd)
        else null
    }
}
