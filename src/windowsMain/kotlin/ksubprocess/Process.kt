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
import cnames.structs._PROC_THREAD_ATTRIBUTE_LIST
import ksubprocess.io.KFileHandle
import ksubprocess.io.WindowsException
import ksubprocess.io.WindowsFileHandle
import ksubprocess.io.brokenPipeSink
import platform.windows.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

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
            dwCreationDisposition = OPEN_EXISTING.convert(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.convert(),
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

    Redirect.Inherit -> {
        // pass an inheritable duplicate so the child can be given an explicit handle list
        val stdId = when (stream) {
            "stdin" -> STD_INPUT_HANDLE
            "stdout" -> STD_OUTPUT_HANDLE
            else -> STD_ERROR_HANDLE
        }
        val std = GetStdHandle(stdId.convert())
        if (std == null || std == INVALID_HANDLE_VALUE) {
            RedirectFds.EMPTY
        } else {
            RedirectFds(std.duplicateInheritable(stream), stream == "stdin")
        }
    }
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
        if (SetHandleInformation(noInheritSide, HANDLE_FLAG_INHERIT.convert(), 0u) == 0) {
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
            dwShareMode = FILE_SHARE_WRITE.convert(),
            lpSecurityAttributes = saAttr.ptr,
            dwCreationDisposition = OPEN_EXISTING.convert(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.convert(),
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
            dwShareMode = FILE_SHARE_WRITE.convert(),
            lpSecurityAttributes = saAttr.ptr,
            dwCreationDisposition = openmode.convert(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.convert(),
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

private fun HANDLE.duplicateInheritable(stream: String): HANDLE? = memScoped {
    val target = alloc<HANDLEVar>()
    val process = GetCurrentProcess()
    if (DuplicateHandle(process, this@duplicateInheritable, process, target.ptr, 0u, TRUE, DUPLICATE_SAME_ACCESS.convert()) == 0) {
        throw ProcessConfigException(
            "Error inheriting $stream",
            WindowsException.fromLastError(functionName = "DuplicateHandle")
        )
    }
    target.value
}

private fun HANDLE?.isValid(): Boolean = this != null && this != INVALID_HANDLE_VALUE

private fun HANDLE?.toFileHandle(write: Boolean): KFileHandle? =
    if (isValid()) WindowsFileHandle(write, this) else null

// Closes the process handle if the Process is dropped before its exit was observed.
private class ProcessHandleRef(handle: HANDLE) {
    private val ref = AtomicReference<HANDLE?>(handle)

    val handle: HANDLE?
        get() = ref.load()

    fun close() {
        ref.exchange(null)?.close(ignoreErrors = true)
    }
}

public actual class Process actual constructor(public actual val args: ProcessArguments) {

    private val processRef: ProcessHandleRef

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
            val childProcessHandle = memScoped {
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

                // only let the child inherit its own standard handles, not those of concurrently started children
                val inherited = listOf(stdin.readFd, stdout.writeFd, stderr.writeFd).filter { it.isValid() }.distinct()
                val attributeListSize = alloc<SIZE_TVar>()
                InitializeProcThreadAttributeList(null, 1u, 0u, attributeListSize.ptr)
                val attributeList = allocArray<ByteVar>(attributeListSize.value.toLong())
                    .reinterpret<_PROC_THREAD_ATTRIBUTE_LIST>()
                if (InitializeProcThreadAttributeList(attributeList, 1u, 0u, attributeListSize.ptr) == 0) {
                    throw ProcessException(
                        "Error starting subprocess",
                        WindowsException.fromLastError(functionName = "InitializeProcThreadAttributeList")
                    )
                }
                try {
                    val handleList = allocArray<HANDLEVar>(inherited.size.coerceAtLeast(1))
                    inherited.forEachIndexed { i, handle -> handleList[i] = handle }
                    if (inherited.isNotEmpty() &&
                        UpdateProcThreadAttribute(
                            attributeList,
                            0u,
                            PROC_THREAD_ATTRIBUTE_HANDLE_LIST.convert(),
                            handleList,
                            (sizeOf<HANDLEVar>() * inherited.size).convert(),
                            null,
                            null
                        ) == 0
                    ) {
                        throw ProcessException(
                            "Error starting subprocess",
                            WindowsException.fromLastError(functionName = "UpdateProcThreadAttribute")
                        )
                    }

                    // populate startupinfo
                    val startInfo = alloc<STARTUPINFOEXW>()
                    startInfo.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().convert()
                    startInfo.StartupInfo.hStdError = stderr.writeFd
                    startInfo.StartupInfo.hStdOutput = stdout.writeFd
                    startInfo.StartupInfo.hStdInput = stdin.readFd
                    startInfo.StartupInfo.dwFlags = STARTF_USESTDHANDLES.convert()
                    startInfo.lpAttributeList = attributeList

                    // start the process
                    val createSuccess = CreateProcessW(
                        lpApplicationName = null, // lpApplicationName
                        lpCommandLine = cmdLine.wcstr.ptr, // command line
                        lpProcessAttributes = null, // process security attributes
                        lpThreadAttributes = null, // primary thread security attributes
                        bInheritHandles = if (inherited.isEmpty()) FALSE else TRUE,
                        dwCreationFlags = (CREATE_UNICODE_ENVIRONMENT or EXTENDED_STARTUPINFO_PRESENT).convert(),
                        lpEnvironment = envBlock, // use environment
                        lpCurrentDirectory = args.workingDirectory, // use current directory if any (null auto propagation)
                        lpStartupInfo = startInfo.StartupInfo.ptr, // STARTUPINFO pointer
                        lpProcessInformation = procInfo.ptr, // receives PROCESS_INFORMATION
                    )
                    if (createSuccess == 0) {
                        throw ProcessException(
                            "Error staring subprocess",
                            WindowsException.fromLastError(functionName = "CreateProcessW")
                        )
                    }
                } finally {
                    DeleteProcThreadAttributeList(attributeList)
                }
                // close thread handle - we don't use it
                procInfo.hThread?.close()
                // pass process handle to childProcessHandle field
                procInfo.hProcess!!
            }

            processRef = ProcessHandleRef(childProcessHandle)

            // wait for the process to initialize
            WaitForInputIdle(childProcessHandle, INFINITE)

            // store file descriptors
            stdoutFd = stdout.readFd
            stderrFd = stderr.readFd
            stdinFd = stdin.writeFd

            // close unused fds, stderr shares the stdout handle when merged
            stdout.writeFd.close(ignoreErrors = true)
            if (args.stderr != Redirect.Stdout) {
                stderr.writeFd.close(ignoreErrors = true)
            }
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

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused")
    private val cleaner = createCleaner(processRef) { it.close() }

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

    // Output pipes are left open so remaining output can still be read; their streams release them at EOF or close.
    private fun cleanup() {
        processRef.close()
        stdinHandle?.close()
    }

    // guards the process handle, which is closed once the exit is observed
    private val stateLock = NativeLock()

    @Volatile
    private var _exitCode: Int? = null

    public actual val exitCode: Int?
        get() {
            _exitCode?.let { return it }
            return stateLock.withLock { exitCodeLocked() }
        }

    private fun exitCodeLocked(): Int? {
        if (_exitCode != null) return _exitCode
        val handle = processRef.handle ?: return _exitCode
        if (WaitForSingleObject(handle, 0u) != WAIT_OBJECT_0) return null
        memScoped {
            val ecVar = alloc<DWORDVar>()
            if (GetExitCodeProcess(handle, ecVar.ptr) == 0) {
                throw ProcessException(
                    "Error querying subprocess state",
                    WindowsException.fromLastError(functionName = "GetExitCodeProcess")
                )
            }
            _exitCode = ecVar.value.toInt()
        }
        cleanup()
        return _exitCode
    }

    public actual val isAlive: Boolean
        get() = exitCode == null

    public actual suspend fun waitFor(): Int {
        // poll instead of blocking so callers' threads stay free and the wait stays cancellable
        var pollDelay = 1.milliseconds
        while (true) {
            exitCode?.let { return it }
            delay(pollDelay)
            pollDelay = (pollDelay * 2).coerceAtMost(POLLING_DELAY)
        }
    }

    public actual suspend fun waitFor(timeout: Duration): Int? {
        require(timeout.isPositive()) { "Timeout must be positive!" }
        val end = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            exitCode?.let { return it }
            if (end.hasPassedNow()) return null
            delay(POLLING_DELAY)
        }
    }

    public actual fun terminate() {
        stateLock.withLock {
            if (exitCodeLocked() != null) return
            if (TerminateProcess(processRef.handle, 1u) == 0) {
                throw ProcessException(
                    "Error terminating child process",
                    WindowsException.fromLastError(functionName = "TerminateProcess")
                )
            }
        }
    }

    public actual fun kill() {
        // Windows has no difference here
        terminate()
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
