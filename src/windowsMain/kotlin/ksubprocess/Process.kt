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
import ksubprocess.io.WindowsException
import ksubprocess.io.WindowsFileHandle
import okio.*
import platform.windows.*
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
            lpFileName = "NUL",
            dwDesiredAccess = GENERIC_READ or GENERIC_WRITE.convert(),
            dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).convert(),
            lpSecurityAttributes = saAttr.ptr,
            dwCreationDisposition = OPEN_EXISTING,
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL,
            hTemplateFile = null
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
            lpFileName = file,
            dwDesiredAccess = GENERIC_READ,
            dwShareMode = FILE_SHARE_WRITE,
            lpSecurityAttributes = saAttr.ptr,
            dwCreationDisposition = OPEN_EXISTING,
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL,
            hTemplateFile = null
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

        val openmode = if (append) OPEN_ALWAYS else CREATE_ALWAYS

        val fd = CreateFileW(
            lpFileName = file,
            dwDesiredAccess = GENERIC_WRITE.convert(),
            dwShareMode = FILE_SHARE_WRITE,
            lpSecurityAttributes = saAttr.ptr,
            dwCreationDisposition = openmode.convert(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL,
            hTemplateFile = null
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

public actual class Process actual constructor(public actual val args: ProcessArguments) {

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
            stderr = if (args.stderr == Redirect.Stdout) {
                RedirectFds(INVALID_HANDLE_VALUE, stdout.writeFd)
            } else {
                args.stderr.openFds("stderr")
            }
            stdin = args.stdin.openFds("stdin")

            // create child process in mem scope
            childProcessHandle = memScoped {
                // convert command line
                val cmdLine = args.arguments.joinToString(" ") { arg ->
                    val quoteArg = arg // .replace("\"", "^\"") TODO find generic solution
                    if (' ' in quoteArg) "\"${quoteArg}\""
                    else quoteArg
                }
                // create env block if needed
                val envBlock = args.environment?.let { env ->
                    // allocate block memory
                    val charCount = env.entries.sumOf { (k, v) -> k.length + 1 + v.length + 1 }
                    val block = allocArray<WCHARVar>(charCount + 1)
                    // fill block with strings
                    var cursor: CArrayPointer<WCHARVar>? = block
                    for ((key, value) in env) {
                        lstrcpyW(cursor, key)
                        cursor += key.length
                        lstrcpyW(cursor, "=")
                        cursor += 1
                        lstrcpyW(cursor, value)
                        cursor += value.length
                        cursor?.set(0, 0u)
                        cursor += 1
                    }
                    // set final 0 terminator
                    cursor?.set(0, 0u)

                    block
                }

                // create process handle receiver
                val procInfo = alloc<PROCESS_INFORMATION>()
                // populate startupinfo
                val startInfo = alloc<STARTUPINFOW> {
                    cb = sizeOf<STARTUPINFOW>().convert()
                    hStdError = stderr.writeFd
                    hStdOutput = stdout.writeFd
                    hStdInput = stdin.readFd
                    dwFlags = dwFlags or STARTF_USESTDHANDLES.convert()
                }

                // start the process
                val createSuccess = CreateProcessW(
                    null, // lpApplicationName
                    cmdLine.wcstr.ptr, // command line
                    null, // process security attributes
                    null, // primary thread security attributes
                    TRUE, // handles are inherited
                    CREATE_UNICODE_ENVIRONMENT, // creation flags
                    envBlock, // use environment
                    args.workingDirectory, // use current directory if any (null auto propagation)
                    startInfo.ptr, // STARTUPINFO pointer
                    procInfo.ptr // receives PROCESS_INFORMATION
                )
                if (createSuccess == 0) {
                    throw ProcessException(
                        "Error staring subprocess",
                        WindowsException.fromLastError(functionName = "CreateProcessW")
                    )
                }
                // close thread handle - we don't use it
                procInfo.hThread?.close()
                // pass process handle to childProcessHandle field
                procInfo.hProcess!!
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
    }

    private val stdinHandle: FileHandle? by lazy {
        if (stdinFd == null) null else WindowsFileHandle(true, stdinFd)
    }
    private val stdoutHandle: FileHandle? by lazy {
        if (stdoutFd == null) null else WindowsFileHandle(false, stdoutFd)
    }
    private val stderrHandle: FileHandle? by lazy {
        if (stderrFd == null) null else WindowsFileHandle(false, stderrFd)
    }

    actual val stdin: BufferedSink? by lazy {
        stdinHandle?.sink()?.buffer()
    }
    actual val stdout: BufferedSource? by lazy {
        stdoutHandle?.source()?.buffer()
    }
    actual val stderr: BufferedSource? by lazy {
        stderrHandle?.source()?.buffer()
    }

    actual val stdoutLines: Flow<String>
        get() = stdout.lines()

    actual val stderrLines: Flow<String>
        get() = stderr.lines()

    // close handles when done!
    private fun cleanup() {
        childProcessHandle.close(ignoreErrors = true)
        stdinFd.close(ignoreErrors = true)
        stdoutFd.close(ignoreErrors = true)
        stderrFd.close(ignoreErrors = true)
        runCatching { stdin?.close() }
        runCatching { stderr?.close() }
        runCatching { stdout?.close() }
        runCatching { stdinHandle?.close() }
        runCatching { stdoutHandle?.close() }
        runCatching { stderrHandle?.close() }
    }

    private var _exitCode: Int? = null
    public actual val exitCode: Int?
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

    public actual val isAlive: Boolean
        get() = exitCode == null

    public actual suspend fun waitFor(): Int {
        return when (WaitForSingleObject(childProcessHandle, INFINITE)) {
            WAIT_FAILED -> throw ProcessException(
                "Error waiting for subprocess",
                WindowsException.fromLastError(functionName = "TerminateProcess")
            )
            else -> checkNotNull(exitCode) { "Waited for process, but it's still alive!" }
        }
    }

    public actual suspend fun waitFor(timeout: Duration): Int? {
        return when (WaitForSingleObject(childProcessHandle, timeout.inWholeMilliseconds.convert())) {
            WAIT_FAILED -> throw ProcessException(
                "Error waiting for child process",
                WindowsException.fromLastError(functionName = "TerminateProcess")
            )
            WAIT_TIMEOUT.convert<DWORD>() -> null // still alive
            else -> exitCode // terminated
        }
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

    actual fun closeStdin() {
        stdin?.close()
        stdinHandle?.close()
    }
}
