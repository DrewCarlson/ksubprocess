/*
 * Copyright 2021 Drew Carlson
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
import ksubprocess.io.*
import platform.Foundation.*
import platform.posix.*
import kotlin.native.concurrent.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

private fun Int.closeFd() {
    if (this != -1) close(this)
}

private data class RedirectFds(val readFd: Int, val writeFd: Int) {
    constructor(fd: Int, isRead: Boolean) : this(
        if (isRead) fd else -1,
        if (isRead) -1 else fd
    )
}

private fun Redirect.openFds(stream: String): RedirectFds = when (this) {
    Redirect.Null -> {
        val fd = open("/dev/null", O_RDWR or O_CLOEXEC)
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
        // macOS has no pipe2, so there is a short window where a concurrent fork can inherit these
        fds.forEach { fcntl(it, F_SETFD, FD_CLOEXEC) }
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
    Redirect.Stdout -> error("Redirect.Stdout must be handled separately.")
}

private fun findExecutable(name: String): String? =
    Environment["PATH"]?.split(':')
        ?.map { "$it/$name" }
        ?.firstOrNull { access(it, F_OK or X_OK) == 0 }

// The task only borrows the descriptor; it is closed explicitly after launch.
private fun Int.toTaskHandle(): NSFileHandle? =
    if (this == -1) null else NSFileHandle(fileDescriptor = this, closeOnDealloc = false)

public actual class Process actual constructor(public actual val args: ProcessArguments) {

    private val task = NSTask()

    internal val stdoutFd: Int
    internal val stderrFd: Int
    private val stdinFd: Int

    init {
        var executable = args.arguments[0]
        if ('/' !in executable) {
            executable = findExecutable(executable)
                ?: throw ProcessConfigException("Unable to find executable '$executable' on PATH")
        }

        args.workingDirectory?.let {
            val dir = opendir(it) ?: throw ProcessConfigException("Working directory '$it' cannot be used!")
            closedir(dir)
        }

        var stdout: RedirectFds? = null
        var stderr: RedirectFds? = null
        var stdin: RedirectFds? = null
        try {
            stdout = args.stdout.openFds("stdout")
            stderr = if (args.stderr == Redirect.Stdout) {
                RedirectFds(-1, stdout.writeFd)
            } else {
                args.stderr.openFds("stderr")
            }
            stdin = args.stdin.openFds("stdin")
            // report EPIPE instead of raising SIGPIPE when the child stops reading
            if (stdin.writeFd != -1) fcntl(stdin.writeFd, F_SETNOSIGPIPE, 1)
            @Suppress("UNCHECKED_CAST")
            task.environment = args.environment as? Map<Any?, *>
            task.executableURL = NSURL.fileURLWithPath(executable)
            task.arguments = args.arguments.drop(1)
            stdout.writeFd.toTaskHandle()?.let { task.standardOutput = it }
            stdin.readFd.toTaskHandle()?.let { task.standardInput = it }
            stderr.writeFd.toTaskHandle()?.let { task.standardError = it }
            args.workingDirectory?.let { task.currentDirectoryURL = NSURL.fileURLWithPath(it, isDirectory = true) }

            // launchAndReturnError reports failures as NSError instead of raising an Objective-C exception
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!task.launchAndReturnError(error.ptr)) {
                    throw ProcessException("Error starting subprocess: ${error.value?.localizedDescription}")
                }
            }

            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            stdout.writeFd.closeFd()
            if (args.stderr != Redirect.Stdout) {
                stderr.writeFd.closeFd()
            }
            stdin.readFd.closeFd()
        } catch (e: Throwable) {
            stdout?.readFd?.closeFd()
            stdout?.writeFd?.closeFd()
            if (args.stderr != Redirect.Stdout) {
                stderr?.readFd?.closeFd()
                stderr?.writeFd?.closeFd()
            }
            stdin?.readFd?.closeFd()
            stdin?.writeFd?.closeFd()
            throw e
        }
    }

    public actual val isAlive: Boolean
        get() = task.isRunning()

    public actual val exitCode: Int?
        get() = if (task.isRunning()) null else task.terminationStatus

    public actual suspend fun waitFor(): Int {
        // poll instead of waitUntilExit so callers' threads stay free and the wait stays cancellable
        var pollDelay = 1.milliseconds
        while (task.isRunning()) {
            delay(pollDelay)
            pollDelay = (pollDelay * 2).coerceAtMost(POLLING_DELAY)
        }
        return task.terminationStatus
    }

    public actual suspend fun waitFor(timeout: Duration): Int? {
        require(timeout.isPositive()) { "Timeout must be positive!" }
        // there is no good blocking solution, so use an active loop with sleep in between.
        val end = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            // return if done or now passed the deadline
            if (!task.isRunning()) return task.terminationStatus
            if (end.hasPassedNow()) return null
            delay(POLLING_DELAY)
        }
    }

    private val stdinHandle: KFileHandle? = stdinFd.toFileHandle(write = true)
    private val stdoutHandle: KFileHandle? = stdoutFd.toFileHandle(write = false)
    private val stderrHandle: KFileHandle? = stderrFd.toFileHandle(write = false)

    public actual val stdin: Sink? by lazy { stdinHandle?.let { it.ownedSinkOrNull() ?: brokenPipeSink() } }
    public actual val stdout: Source? by lazy { stdoutHandle?.ownedSource() }
    public actual val stderr: Source? by lazy { stderrHandle?.ownedSource() }

    public actual val stdoutLines: Flow<String>
        get() = stdout.lines()

    public actual val stderrLines: Flow<String>
        get() = stderr.lines()

    public actual fun terminate() {
        task.terminate()
    }

    public actual fun kill() {
        if (task.isRunning()) {
            kill(task.processIdentifier, SIGKILL)
        }
    }

    public actual fun closeStdin() {
        stdin?.close()
        stdinHandle?.close()
    }

    internal actual fun drainOutput() {
        stdoutHandle?.stopAfterAvailable()
        stderrHandle?.stopAfterAvailable()
    }
}
