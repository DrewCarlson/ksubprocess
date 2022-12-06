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
import ksubprocess.io.*
import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import okio.buffer
import platform.Foundation.*
import platform.posix.*
import kotlin.native.concurrent.*
import kotlin.time.*

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
        val fd = open("/dev/null", O_RDWR)
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
        val handle = checkNotNull(NSFileHandle.fileHandleForReadingAtPath(file)) {
            "Failed to create NSFileHandle for '$file'"
        }
        RedirectFds(handle.fileDescriptor, -1)
    }
    is Redirect.Write -> {
        if (!NSFileManager.defaultManager.fileExistsAtPath(file)) {
            NSFileManager.defaultManager.createFileAtPath(file, null, null)
        }
        val handle = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(file)) {
            "Failed to create NSFileHandle for '$file'"
        }
        RedirectFds(-1, handle.fileDescriptor)
    }
    Redirect.Stdout -> error("Redirect.Stdout must be handled separately.")
}

actual class Process actual constructor(actual val args: ProcessArguments) {

    private val task = NSTask()

    internal val stdoutFd: Int
    internal val stderrFd: Int
    private val stdinFd: Int

    init {
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
            @Suppress("UNCHECKED_CAST")
            task.environment = args.environment as? Map<Any?, *>
            task.setLaunchPath(args.arguments.firstOrNull())
            task.arguments = args.arguments.drop(1)
            task.standardOutput = NSFileHandle(stdout.writeFd, true)
            task.standardInput = NSFileHandle(stdin.readFd, true)
            task.standardError = NSFileHandle(stderr.writeFd, true)
            args.workingDirectory?.run(task::setCurrentDirectoryPath)
            task.launch()

            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            stdout.writeFd.closeFd()
            stderr.writeFd.closeFd()
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

    actual val isAlive: Boolean
        get() = task.isRunning()

    actual val exitCode: Int?
        get() = if (task.isRunning()) null else task.terminationStatus

    actual suspend fun waitFor(): Int {
        task.waitUntilExit()
        return task.terminationStatus
    }

    @OptIn(ExperimentalTime::class)
    actual suspend fun waitFor(timeout: Duration): Int? {
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

    private val stdinHandle: FileHandle? by lazy {
        if (stdinFd != -1) {
            OkioNSFileHandle(true, NSFileHandle(stdinFd, true))
        } else null
    }
    private val stdoutHandle: FileHandle? by lazy {
        if (stdoutFd != -1) {
            OkioNSFileHandle(false, NSFileHandle(stdoutFd, true))
        } else null
    }
    private val stderrHandle: FileHandle? by lazy {
        if (stderrFd != -1) {
            OkioNSFileHandle(false, NSFileHandle(stderrFd, true))
        } else null
    }

    actual val stdin: BufferedSink? by lazy { stdinHandle?.sink()?.buffer() }
    actual val stdout: BufferedSource? by lazy { stdoutHandle?.source()?.buffer() }
    actual val stderr: BufferedSource? by lazy { stderrHandle?.source()?.buffer() }

    actual val stdoutLines: Flow<String>
        get() = stdout.lines()

    actual val stderrLines: Flow<String>
        get() = stderr.lines()

    actual fun terminate() {
        task.terminate()
    }

    actual fun kill() {
        task.terminate()
    }

    actual fun closeStdin() {
        stdin?.close()
        stdinHandle?.close()
    }
}
