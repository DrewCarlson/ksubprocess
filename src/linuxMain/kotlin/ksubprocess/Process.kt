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
import kotlinx.io.Sink
import kotlinx.io.Source
import ksubprocess.io.KFileHandle
import ksubprocess.io.PosixException
import ksubprocess.io.brokenPipeSink
import ksubprocess.io.toFileHandle
import ksubprocess.iop.KSP_STAGE_CHDIR
import ksubprocess.iop.KSP_STAGE_REDIRECT
import ksubprocess.iop.fork_and_run
import ksubprocess.iop.pipe_cloexec
import ksubprocess.iop.read_failure
import platform.posix.*
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

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
        val fd = open("/dev/null", flags or O_CLOEXEC)
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
            pipe_cloexec(it.addressOf(0))
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
        val fd = open(file, O_RDONLY or O_CLOEXEC)
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
            if (append) O_WRONLY or O_APPEND or O_CREAT or O_CLOEXEC
            else O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC,
            "666".toInt(8)
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

// Reaping state shared with a cleaner, so a dropped Process does not leave a zombie behind.
private class Child(val pid: pid_t) {
    val lock = NativeLock()
    var reaped = false

    fun abandon() {
        lock.withLock {
            if (!reaped) AbandonedChildren.add(pid)
        }
    }
}

// Children whose Process was collected before their exit was observed.
private object AbandonedChildren {
    private val lock = NativeLock()
    private val pids = mutableListOf<pid_t>()

    fun add(pid: pid_t) {
        lock.withLock { pids += pid }
        reap()
    }

    fun reap() {
        lock.withLock {
            if (pids.isNotEmpty()) pids.removeAll { waitpid(it, null, WNOHANG) != 0 }
        }
    }
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
    @Volatile
    private var terminated = false

    // exit status - only valid once terminated = true
    private var _exitStatus = -1

    private val child: Child

    // file descriptors for child pipes
    internal val stdoutFd: Int
    internal val stderrFd: Int
    private val stdinFd: Int

    init {
        AbandonedChildren.reap()

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
        var status: RedirectFds? = null
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
            // the child reports exec failures here, a successful exec closes it
            status = Redirect.Pipe.openFds("exec status")

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
                    err_fd = status.writeFd,
                )
            }
            if (pid == -1) {
                // fork failed
                throw ProcessException(
                    "Error staring subprocess",
                    PosixException.forErrno("fork")
                )
            }
            status.writeFd.closeFd()
            status = status.copy(writeFd = -1)
            checkLaunched(pid, status.readFd, executable)
            child = Child(pid)

            // store file descriptors
            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            // close unused fds, stderr shares the stdout fd when merged
            stdout.writeFd.closeFd()
            if (args.stderr != Redirect.Stdout) {
                stderr.writeFd.closeFd()
            }
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
        } finally {
            status?.readFd?.closeFd()
            status?.writeFd?.closeFd()
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(child) { it.abandon() }

    private fun checkLaunched(pid: pid_t, statusFd: Int, executable: String) {
        val (result, stage, error) = memScoped {
            val stage = alloc<IntVar>()
            val error = alloc<IntVar>()
            Triple(read_failure(statusFd, stage.ptr, error.ptr), stage.value, error.value)
        }
        if (result == 0) return
        val readError = errno
        if (result == -1) kill(pid, SIGKILL)
        waitpid(pid, null, 0)
        throw when {
            result == -1 -> ProcessException(
                "Error starting subprocess",
                PosixException.forErrno("read", readError)
            )
            stage == KSP_STAGE_CHDIR -> ProcessConfigException(
                "Working directory '${args.workingDirectory}' cannot be used!",
                PosixException.forErrno("chdir", error)
            )
            stage == KSP_STAGE_REDIRECT -> ProcessException(
                "Error redirecting standard streams",
                PosixException.forErrno("dup2", error)
            )
            else -> ProcessConfigException(
                "Error executing '$executable'",
                PosixException.forErrno("execv", error)
            )
        }
    }

    private val stdinHandle: KFileHandle? = stdinFd.toFileHandle(write = true)
    private val stdoutHandle: KFileHandle? = stdoutFd.toFileHandle(write = false)
    private val stderrHandle: KFileHandle? = stderrFd.toFileHandle(write = false)

    // Output pipes are left open so remaining output can still be read; their streams release them at EOF or close.
    private fun cleanup() {
        stdinHandle?.close()
    }

    private fun checkState() {
        if (terminated) return
        child.lock.withLock { checkStateLocked() }
    }

    private fun checkStateLocked() {
        if (terminated) return
        memScoped {
            val info = alloc<siginfo_t>()
            val res = waitid(idtype_t.P_PID, child.pid.convert(), info.ptr, WNOHANG or WEXITED)
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
                    child.reaped = true
                    terminated = true
                    // report signals like the JVM and shells do: 128 + signal number
                    _exitStatus = if (info.si_code == CLD_EXITED) info.exitCode else 128 + info.exitCode
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
        // poll instead of blocking in waitid so callers' threads stay free and the wait stays cancellable
        var pollDelay = 1.milliseconds
        while (true) {
            checkState()
            if (terminated) return _exitStatus
            delay(pollDelay)
            pollDelay = (pollDelay * 2).coerceAtMost(POLLING_DELAY)
        }
    }

    public actual suspend fun waitFor(timeout: Duration): Int? {
        require(timeout.isPositive()) { "Timeout must be positive!" }
        // there is no good blocking solution, so use an active loop with sleep in between.
        val end = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            checkState()
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
        // hold the lock so the pid cannot be reaped and reused by another process meanwhile
        child.lock.withLock {
            if (terminated) return
            if (kill(child.pid, signal) != 0) {
                throw ProcessException(
                    "Error terminating process",
                    PosixException.forErrno("kill")
                )
            }
        }
    }

    public actual val stdin: Sink? by lazy { stdinHandle?.let { it.ownedSinkOrNull() ?: brokenPipeSink() } }

    public actual val stdout: Source? by lazy { stdoutHandle?.ownedSource() }

    public actual val stderr: Source? by lazy { stderrHandle?.ownedSource() }

    public actual val stdoutLines: Flow<String>
        get() = stdout.lines()

    public actual val stderrLines: Flow<String>
        get() = stderr.lines()

    public actual fun closeStdin() {
        stdin?.close()
        stdinHandle?.close()
    }

    internal actual fun drainOutput() {
        stdoutHandle?.stopAfterAvailable()
        stderrHandle?.stopAfterAvailable()
    }
}
