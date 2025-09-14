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

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import ksubprocess.io.PosixException
import ksubprocess.io.UnixFileHandle
import ksubprocess.iop.fork_and_run
import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import okio.buffer
import platform.posix.*
import kotlin.time.*

internal expect val siginfo_t.exitCode: Int

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
        val flags = if (stream == "stdin") O_RDONLY else O_WRONLY
        val fd = open("/dev/null", flags)
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening null file for $stream",
                PosixException.forErrno("open")
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
                PosixException.forErrno("pipe")
            )
        }
        RedirectFds(fds[0], fds[1])
    }

    is Redirect.Read -> {
        val fd = open(file, O_RDONLY)
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening input file $file for $stream",
                PosixException.forErrno("open")
            )
        }
        RedirectFds(fd, -1)
    }

    is Redirect.Write -> {
        val fd = open(
            file,
            if (append) O_WRONLY or O_APPEND or O_CREAT
            else O_WRONLY or O_CREAT or O_TRUNC,
            0x0777
        )
        if (fd == -1) {
            throw ProcessConfigException(
                "Error opening output file $file for $stream",
                PosixException.forErrno("open")
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

public actual class Process actual constructor(
    public actual val args: ProcessArguments
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
            stderr = if (args.stderr == Redirect.Stdout) {
                // use stdout
                RedirectFds(-1, stdout.writeFd)
            } else {
                args.stderr.openFds("stderr")
            }
            stdin = args.stdin.openFds("stdin")

            val pid = memScoped {
                // convert c lists
                val arguments = toCStrVector(args.arguments)
                val env = args.environment?.let { toCStrVector(it.map { e -> "${e.key}=${e.value}" }) }

                fork_and_run(
                    executable = executable,
                    args = arguments,
                    cd = args.workingDirectory,
                    env = env,
                    stdout_fd = stdout.writeFd,
                    stderr_fd = stderr.writeFd,
                    stdin_fd = stdin.readFd,
                    op_stdout = stdout.readFd,
                    op_stderr = stderr.readFd,
                    op_stdin = stdin.writeFd
                )
            }
            if (pid == -1) {
                // fork failed
                throw ProcessException(
                    "Error staring subprocess",
                    PosixException.forErrno("fork")
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
    }

    private fun cleanup() {
        stdoutHandle?.close()
        stderrHandle?.close()
        stdinHandle?.close()
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
                    PosixException.forErrno("waitpid")
                )
            }
            when (info.si_code) {
                CLD_EXITED, CLD_KILLED, CLD_DUMPED -> {
                    // process has terminated
                    terminated = true
                    _exitStatus = info.exitCode
                    cleanup()
                }
            }
            // else we are not done
        }
    }

    public actual val isAlive: Boolean
        get() {
            checkState()
            return !terminated
        }
    public actual val exitCode: Int?
        get() {
            checkState()
            return if (terminated) _exitStatus else null
        }

    public actual suspend fun waitFor(): Int {
        while (!terminated) {
            checkState(true)
        }
        return _exitStatus
    }

    public actual suspend fun waitFor(timeout: Duration): Int? {
        require(timeout.isPositive()) { "Timeout must be positive!" }
        // there is no good blocking solution, so use an active loop with sleep in between.
        val end = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            checkState(false)
            // return if done or now passed the deadline
            if (terminated) return _exitStatus
            if (end.hasPassedNow()) return null
            delay(POLLING_DELAY)
        }
    }

    public actual fun terminate() {
        sendSignal(SIGTERM)
    }

    public actual fun kill() {
        sendSignal(SIGKILL)
    }

    /**
     * Send the given signal to the child process.
     *
     * @param signal signal number
     */
    public fun sendSignal(signal: Int) {
        if (terminated) return
        if (kill(childPid, signal) != 0) {
            throw ProcessException(
                "Error terminating process",
                PosixException.forErrno("kill")
            )
        }
    }

    private val stdinHandle: FileHandle? by lazy {
        if (stdinFd != -1) {
            val fd = fdopen(stdinFd, "w") ?: throw PosixException.forErrno("fdopen")
            UnixFileHandle(true, fd)
        } else null
    }
    private val stdoutHandle: FileHandle? by lazy {
        if (stdoutFd != -1) {
            val fd = fdopen(stdoutFd, "r") ?: throw PosixException.forErrno("fdopen")
            UnixFileHandle(false, fd)
        } else null
    }
    private val stderrHandle: FileHandle? by lazy {
        if (stderrFd != -1) {
            val fd = fdopen(stderrFd, "r") ?: throw PosixException.forErrno("fdopen")
            UnixFileHandle(false, fd)
        } else null
    }

    public actual val stdin: BufferedSink? by lazy { stdinHandle?.sink()?.buffer() }

    public actual val stdout: BufferedSource? by lazy { stdoutHandle?.source()?.buffer() }

    public actual val stderr: BufferedSource? by lazy { stderrHandle?.source()?.buffer() }

    public actual val stdoutLines: Flow<String>
        get() = stdout.lines()

    public actual val stderrLines: Flow<String>
        get() = stderr.lines()

    public actual fun closeStdin() {
        stdin?.close()
        stdinHandle?.close()
    }
}
