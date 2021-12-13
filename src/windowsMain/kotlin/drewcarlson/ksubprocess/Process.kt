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

package drewcarlson.ksubprocess

import drewcarlson.ksubprocess.io.Input
import drewcarlson.ksubprocess.io.Output
import drewcarlson.ksubprocess.io.WindowsException
import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.native.concurrent.*
import kotlin.time.*

// read and write fds for a pipe. Also used to store other fds for convenience.
private data class RedirectFds(val readFd: HANDLE?, val writeFd: HANDLE?) {
    companion object {
        val EMPTY = RedirectFds(INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE)
    }

    constructor(fd: HANDLE?, isRead: Boolean) : this(
        if (isRead) fd else INVALID_HANDLE_VALUE,
        if (isRead) INVALID_HANDLE_VALUE else fd
    )
}

private fun Redirect.openFds(stream: String): RedirectFds = when (this) {
    Redirect.Null -> memScoped {
        val saAttr = alloc<SECURITY_ATTRIBUTES>()
        saAttr.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        saAttr.bInheritHandle = TRUE
        saAttr.lpSecurityDescriptor = NULL

        val fd = CreateFileW(
            "NUL",
            GENERIC_READ or GENERIC_WRITE.convert(),
            (FILE_SHARE_READ or FILE_SHARE_WRITE).convert(),
            saAttr.ptr,
            OPEN_EXISTING,
            0,
            null
        )
        if (fd == INVALID_HANDLE_VALUE) {
            throw ProcessConfigException(
                "Error opening null file for $stream",
                WindowsException.fromLastError(functionName = "CreateFileW()")
            )
        }
        RedirectFds(fd, stream == "stdin")
    }
    Redirect.Inherit -> RedirectFds.EMPTY
    Redirect.Pipe -> memScoped {
        // open a pipe
        val hReadPipe = alloc<HANDLEVar>()
        val hWritePipe = alloc<HANDLEVar>()

        val saAttr = alloc<SECURITY_ATTRIBUTES>()
        saAttr.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        saAttr.bInheritHandle = TRUE
        saAttr.lpSecurityDescriptor = NULL

        if (CreatePipe(hReadPipe.ptr, hWritePipe.ptr, saAttr.ptr, 0u) == 0) {
            throw ProcessException(
                "Error creating $stream pipe",
                WindowsException.fromLastError(functionName = "CreatePipe")
            )
        }
        // only inherit relevant handle
        val noInheritSide = if (stream == "stdin") hWritePipe.value else hReadPipe.value
        if (SetHandleInformation(noInheritSide, HANDLE_FLAG_INHERIT, 0u) == 0) {
            throw ProcessException(
                "Error disinheriting $stream pipe local side",
                WindowsException.fromLastError(functionName = "SetHandleInformation")
            )
        }

        RedirectFds(hReadPipe.value, hWritePipe.value)
    }
    is Redirect.Read -> memScoped {
        val saAttr = alloc<SECURITY_ATTRIBUTES>()
        saAttr.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        saAttr.bInheritHandle = TRUE
        saAttr.lpSecurityDescriptor = NULL

        val fd = CreateFileW(
            file,
            GENERIC_READ,
            0,
            saAttr.ptr,
            OPEN_EXISTING,
            0,
            null
        )
        if (fd == INVALID_HANDLE_VALUE) {
            throw ProcessConfigException(
                "Error opening input file $file for $stream",
                WindowsException.fromLastError(functionName = "CreateFileW")
            )
        }
        RedirectFds(fd, INVALID_HANDLE_VALUE)
    }
    is Redirect.Write -> memScoped {
        val saAttr = alloc<SECURITY_ATTRIBUTES>()
        saAttr.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        saAttr.bInheritHandle = TRUE
        saAttr.lpSecurityDescriptor = NULL

        val openmode = if (append) OPEN_ALWAYS else OPEN_ALWAYS or TRUNCATE_EXISTING

        val fd = CreateFileW(
            file,
            GENERIC_WRITE,
            0,
            saAttr.ptr,
            openmode.convert(),
            0,
            null
        )
        if (fd == INVALID_HANDLE_VALUE) {
            throw ProcessConfigException(
                "Error opening input file $file for $stream",
                WindowsException.fromLastError(functionName = "CreateFileW")
            )
        }
        RedirectFds(INVALID_HANDLE_VALUE, fd)
    }
    Redirect.Stdout -> error("Redirect.Stdout must be handled separately.")
}

actual class Process actual constructor(actual val args: ProcessArguments) {

    private val childProcessHandle: HANDLE
    // file descriptors for child pipes
    internal val stdoutFd: HANDLE?
    internal val stderrFd: HANDLE?
    private val stdinFd: HANDLE?

    init {
        var stdout: RedirectFds = RedirectFds.EMPTY
        var stderr: RedirectFds = RedirectFds.EMPTY
        var stdin: RedirectFds = RedirectFds.EMPTY
        try {
            // init redirects
            stdout = args.stdout.openFds("stdout")
            stderr =
                if (args.stderr == Redirect.Stdout) RedirectFds(
                    INVALID_HANDLE_VALUE,
                    stdout.writeFd
                )
                else args.stderr.openFds("stderr")
            stdin = args.stdin.openFds("stdin")

            // create child process in mem scope
            childProcessHandle = memScoped {
                // convert command line
                val cmdLine = args.arguments.joinToString(" ") { arg ->
                    val quoteArg = arg //.replace("\"", "^\"") TODO find generic solution
                    if (' ' in quoteArg) "\"${quoteArg}\""
                    else quoteArg
                }
                // create env block if needed
                val envBlock = args.environment?.let { env ->
                    // convert to key=value strings
                    val envStrs = env.map { e -> "${e.key}=${e.value}" }

                    // allocate block memory
                    val block = allocArray<WCHARVar>(envStrs.sumOf { it.length + 1 } + 1)
                    // fill block with strings
                    var cursor: CArrayPointer<WCHARVar>? = block
                    for (envStr in envStrs) {
                        lstrcpyW(cursor, envStr)
                        cursor += envStr.length + 1
                    }
                    // set final 0 terminator
                    cursor!![0] = 0u

                    block
                }

                // create process handle receiver
                val piProcInfo = alloc<PROCESS_INFORMATION>()
                // populate startupinfo
                val siStartInfo = alloc<STARTUPINFOW>()
                siStartInfo.cb = sizeOf<STARTUPINFOW>().convert()
                siStartInfo.hStdError = stderr.writeFd
                siStartInfo.hStdOutput = stdout.writeFd
                siStartInfo.hStdInput = stdin.readFd
                siStartInfo.dwFlags = siStartInfo.dwFlags or STARTF_USESTDHANDLES.convert()

                // start the process
                val createSuccess = CreateProcessW(
                    null,                        // lpApplicationName
                    cmdLine.wcstr.ptr,           // command line
                    null,                        // process security attributes
                    null,                        // primary thread security attributes
                    TRUE,                        // handles are inherited
                    CREATE_UNICODE_ENVIRONMENT,  // creation flags
                    envBlock,                    // use environment
                    args.workingDirectory,       // use current directory if any (null auto propagation)
                    siStartInfo.ptr,             // STARTUPINFO pointer
                    piProcInfo.ptr               // receives PROCESS_INFORMATION
                )
                if (createSuccess == 0) {
                    throw ProcessException(
                        "Error staring subprocess",
                        WindowsException.fromLastError(functionName = "CreateProcessW")
                    )
                }
                // close thread handle - we don't use it
                piProcInfo.hThread?.close()
                // pass process handle to childProcessHandle field
                piProcInfo.hProcess!!
            }

            // wait for the process to initialize
            WaitForInputIdle(childProcessHandle, INFINITE)

            // store file descriptors
            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            // close unused fds (don't need to watch stderr=stdout here)
            stdout.writeFd.close(ignoreErrors = true)
            stderr.writeFd.close(ignoreErrors = true)
            stdin.readFd.close(ignoreErrors = true)
        } catch (e: Exception) {
            // cleanup handles
            // close fds on error
            stdout.readFd.close(ignoreErrors = true)
            stdout.writeFd.close(ignoreErrors = true)
            if (args.stderr != Redirect.Stdout) {
                stderr.readFd.close(ignoreErrors = true)
                stderr.writeFd.close(ignoreErrors = true)
            }
            stdin.readFd.close(ignoreErrors = true)
            stdin.writeFd.close(ignoreErrors = true)
            throw e
        }

        if (Platform.memoryModel == MemoryModel.STRICT) {
            args.freeze() // frozen args val since they get shared.
        }
    }

    // close handles when done!
    private fun cleanup() {
        childProcessHandle.close(ignoreErrors = true)
        stdinFd.close(ignoreErrors = true)
        stdoutFd.close(ignoreErrors = true)
        stderrFd.close(ignoreErrors = true)
    }

    private var _exitCode: Int? = null
    actual val exitCode: Int?
        get() {
            if (_exitCode == null) {
                // query process
                memScoped {
                    val ecVar = alloc<DWORDVar>()
                    if (GetExitCodeProcess(childProcessHandle, ecVar.ptr) == 0) {
                        throw ProcessException(
                            "Error querying subprocess state",
                            WindowsException.fromLastError(functionName = "GetExitCodeProcess")
                        )
                    } else if (ecVar.value != STILL_ACTIVE) {
                        cleanup()
                        _exitCode = ecVar.value.convert()
                    }
                }
            }
            return _exitCode
        }

    actual val isAlive: Boolean
        get() = exitCode == null

    actual fun waitFor(): Int = when (WaitForSingleObject(childProcessHandle, INFINITE)) {
        WAIT_FAILED -> throw ProcessException(
            "Error waiting for subprocess",
            WindowsException.fromLastError(functionName = "TerminateProcess")
        )
        else -> exitCode ?: throw IllegalStateException("Waited for process, but it's still alive!")
    }

    @OptIn(ExperimentalTime::class)
    actual fun waitFor(timeout: Duration): Int? =
        when (WaitForSingleObject(childProcessHandle, timeout.inWholeMilliseconds.convert())) {
            WAIT_FAILED -> throw ProcessException(
                "Error waiting for child process",
                WindowsException.fromLastError(functionName = "TerminateProcess")
            )
            WAIT_TIMEOUT.convert<DWORD>() -> null // still alive
            else -> exitCode // terminated
        }

    actual fun terminate() {
        if (TerminateProcess(childProcessHandle, 1) == 0) {
            throw ProcessException(
                "Error terminating child process",
                WindowsException.fromLastError(functionName = "TerminateProcess")
            )
        }
    }

    actual fun kill() {
        // Windows has no difference here
        terminate()
    }

    actual val stdin: Output? by lazy {
        if (stdinFd != INVALID_HANDLE_VALUE) Output(stdinFd)
        else null
    }
    actual val stdout: Input? by lazy {
        if (stdoutFd != INVALID_HANDLE_VALUE) Input(stdoutFd)
        else null
    }
    actual val stderr: Input? by lazy {
        if (stderrFd != INVALID_HANDLE_VALUE) Input(stderrFd)
        else null
    }

}
