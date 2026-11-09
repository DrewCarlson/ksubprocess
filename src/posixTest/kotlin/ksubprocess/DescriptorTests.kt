package ksubprocess

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.posix.F_GETFD
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.dup
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.open
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class DescriptorTests {

    @OptIn(NativeRuntimeApi::class)
    private fun openFdCount(): Int {
        GC.collect()
        val path = if (access("/proc/self/fd", F_OK) == 0) "/proc/self/fd" else "/dev/fd"
        val dir = checkNotNull(opendir(path)) { "Cannot open $path" }
        var count = 0
        while (readdir(dir) != null) count++
        closedir(dir)
        return count
    }

    private fun assertNoFdGrowth(before: Int) {
        val after = openFdCount()
        assertTrue(after <= before + FD_SLACK, "Descriptors grew from $before to $after")
    }

    private fun shell(script: String, builder: ProcessArgumentBuilder.() -> Unit = {}) = Process {
        arg("/bin/sh")
        arg("-c")
        arg(script)
        builder()
    }

    @Test
    fun communicateReleasesPipes() = runBlocking<Unit> {
        repeat(5) { shell("true") { stdout = Redirect.Pipe; stderr = Redirect.Pipe }.communicate() }
        val before = openFdCount()
        repeat(100) {
            val result = shell("cat > /dev/null; echo out; echo err >&2") {
                stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }.communicate(input = "input")
            assertEquals(CommunicateResult(0, "out\n", "err\n"), result)
        }
        assertNoFdGrowth(before)
    }

    @Test
    fun streamsReadToEndButNotClosedAreReleased() = runBlocking<Unit> {
        val before = openFdCount()
        repeat(100) {
            val proc = shell("echo out; echo err >&2") {
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            assertEquals("out\n", proc.stdout!!.readString())
            assertEquals("err\n", proc.stderr!!.readString())
            assertEquals(0, proc.waitFor())
        }
        assertNoFdGrowth(before)
    }

    @Test
    fun unreadPipesAreReleasedWhenProcessIsCollected() = runBlocking<Unit> {
        val before = openFdCount()
        repeat(100) {
            val proc = shell("echo out; echo err >&2") {
                stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            assertEquals(0, proc.waitFor())
        }
        assertNoFdGrowth(before)
    }

    @Test
    fun outputIsReadableAfterExitIsObserved() = runBlocking<Unit> {
        val proc = shell("echo out; echo err >&2") {
            stdout = Redirect.Pipe
            stderr = Redirect.Pipe
        }
        assertEquals(0, proc.waitFor())
        assertFalse(proc.isAlive)
        assertEquals(0, proc.exitCode)
        assertEquals("out\n", proc.stdout!!.readString())
        assertEquals("err\n", proc.stderr!!.readString())
    }

    @Test
    fun cancellingCommunicateKillsAndReapsChild() = runBlocking<Unit> {
        shell("true") { stdout = Redirect.Pipe; stderr = Redirect.Pipe }.communicate()
        val before = openFdCount()
        val proc = shell("exec sleep 30") {
            stdin = Redirect.Pipe
            stdout = Redirect.Pipe
            stderr = Redirect.Pipe
        }
        val job = launch(Dispatchers.Default) { proc.communicate() }
        delay(500.milliseconds)
        withTimeout(10.seconds) { job.cancelAndJoin() }
        assertFalse(proc.isAlive)
        assertNotNull(proc.exitCode)
        assertNoFdGrowth(before)
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun mergedStderrDoesNotCloseUnrelatedDescriptors() = runBlocking<Unit> {
        val stop = AtomicBoolean(false)
        val failures = AtomicInt(0)
        val checkers = List(8) {
            launch(Dispatchers.Default) {
                val probe = open("/dev/null", O_RDONLY)
                while (!stop.load()) {
                    // grab whatever number is lowest, which is the one a double close would hit
                    val fd = dup(probe)
                    if (fcntl(fd, F_GETFD) == -1) failures.incrementAndFetch()
                    close(fd)
                }
                close(probe)
            }
        }
        try {
            repeat(300) {
                val result = shell("echo out; echo err >&2") {
                    stdout = Redirect.Pipe
                    stderr = Redirect.Stdout
                }.communicate()
                assertEquals(0, result.exitCode)
            }
        } finally {
            stop.store(true)
            checkers.joinAll()
        }
        assertEquals(0, failures.load(), "Descriptors owned by other threads were closed")
    }

    @Test
    fun pipesAreNotInheritedByOtherChildren() = runBlocking<Unit> {
        val holder = shell("exec sleep 30") { stdout = Redirect.Pipe }
        try {
            val fd = holder.stdoutPipeFd
            val inode = memScoped {
                val st = alloc<stat>()
                check(fstat(fd, st.ptr) == 0)
                st.st_ino
            }
            // Linux names the pipe; elsewhere any open descriptor with that number counts as a leak.
            // Emulators such as Rosetta keep their own descriptors open in every process.
            val result = shell(
                "readlink /dev/fd/$fd 2>/dev/null || { [ -e /dev/fd/$fd ] && echo open; } || echo closed"
            ) {
                stdout = Redirect.Pipe
            }.communicate()
            assertTrue(result.output.trim() !in setOf("pipe:[$inode]", "open"), "Pipe leaked: ${result.output}")
        } finally {
            holder.kill()
            holder.waitFor()
            holder.stdout?.close()
        }
    }

    @Test
    fun redirectWriteCreatesPlainFile() = runBlocking<Unit> {
        val path = Path("build", "redirect-mode.txt")
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        shell("echo hi") { stdout = Redirect.Write(path.toString()) }.waitFor()
        val mode = fileMode(path.toString())
        SystemFileSystem.delete(path)
        assertEquals(0, mode and "7000".toInt(8), "Special bits set: ${mode.toString(8)}")
        assertEquals(0, mode and "111".toInt(8), "Execute bits set: ${mode.toString(8)}")
    }

    @Test
    fun redirectWriteTruncatesOrAppends() = runBlocking<Unit> {
        val path = Path("build", "redirect-append.txt")
        shell("echo first-long-line") { stdout = Redirect.Write(path.toString()) }.waitFor()
        shell("echo second") { stdout = Redirect.Write(path.toString()) }.waitFor()
        shell("echo third") { stdout = Redirect.Write(path.toString(), append = true) }.waitFor()
        val content = SystemFileSystem.source(path).buffered().use { it.readString() }
        SystemFileSystem.delete(path)
        assertEquals("second\nthird\n", content)
    }

    @Test
    fun outputIsDeliveredWhileChildIsRunning() = runBlocking<Unit> {
        val proc = shell("echo first; sleep 5; echo second") { stdout = Redirect.Pipe }
        try {
            val (line, elapsed) = withContext(Dispatchers.IO) {
                measureTimedValue { proc.stdoutLines.first() }
            }
            assertEquals("first", line)
            assertTrue(elapsed < 3.seconds, "First line took $elapsed")
            assertTrue(proc.isAlive)
        } finally {
            proc.kill()
            proc.waitFor()
        }
    }

    @Test
    fun multiByteInputSurvivesSegmentBoundaries() = runBlocking<Unit> {
        val input = "a\u00e4\u20ac\ud83d\ude00".repeat(5000)
        val result = shell("cat") {
            stdin = Redirect.Pipe
            stdout = Redirect.Pipe
        }.communicate(input = input)
        assertEquals(input, result.output)
    }

    @Test
    fun readIntoPartiallyFilledSegmentStaysInBounds() = runBlocking<Unit> {
        val proc = shell("printf abc; sleep 1; head -c 20000 /dev/zero | tr '\\0' b") { stdout = Redirect.Pipe }
        val stdout = proc.stdout!!
        // keep "abc" buffered so the next read lands in the middle of a segment
        stdout.require(3)
        val output = stdout.readString()
        proc.waitFor()
        assertEquals("abc" + "b".repeat(20000), output)
    }

    @Test
    fun writeFromPartiallyConsumedSegmentSendsExactBytes() = runBlocking<Unit> {
        val proc = shell("cat") {
            stdin = Redirect.Pipe
            stdout = Redirect.Pipe
        }
        val expected = (0 until 20000).map { 'a' + it % 26 }.joinToString("")
        val buffer = Buffer().apply { writeString("x$expected") }
        buffer.readByte()
        val stdin = proc.stdin!!
        stdin.write(buffer, buffer.size)
        stdin.flush()
        proc.closeStdin()
        assertEquals(expected, proc.stdout!!.readString())
        proc.waitFor()
    }

    @Test
    fun communicateReturnsWhenChildExitsWhileBackgroundProcessHoldsPipes() = runBlocking<Unit> {
        val (result, elapsed) = measureTimedValue {
            shell("sleep 30 & echo out; echo err >&2") {
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }.communicate()
        }
        assertEquals(CommunicateResult(0, "out\n", "err\n"), result)
        assertTrue(elapsed < 10.seconds, "communicate took $elapsed")
    }

    @Test
    fun communicateKeepsAllOutputWrittenBeforeExit() = runBlocking<Unit> {
        repeat(10) {
            val result = shell("head -c 1000000 /dev/zero | tr '\\0' a; printf end") {
                stdout = Redirect.Pipe
                stderr = Redirect.Stdout
            }.communicate()
            assertEquals(1_000_003, result.output.length)
            assertTrue(result.output.endsWith("aend"))
        }
    }

    @Test
    fun cancellingCommunicateReturnsWhileBackgroundProcessHoldsPipes() = runBlocking<Unit> {
        val proc = shell("sleep 30 & exec sleep 30") {
            stdout = Redirect.Pipe
            stderr = Redirect.Pipe
        }
        val job = launch(Dispatchers.Default) { proc.communicate() }
        delay(500.milliseconds)
        withTimeout(10.seconds) { job.cancelAndJoin() }
        assertFalse(proc.isAlive)
    }

    @Test
    fun communicateIgnoresChildNotReadingInput() = runBlocking<Unit> {
        val input = "x".repeat(1_000_000)
        repeat(20) {
            val result = shell("exit 3") {
                stdin = Redirect.Pipe
                stdout = Redirect.Pipe
            }.communicate(input = input)
            assertEquals(CommunicateResult(3, "", ""), result)
        }
    }

    @Test
    fun stdinAfterExitBehavesAsBrokenPipe() = runBlocking<Unit> {
        val proc = shell("echo done") {
            stdin = Redirect.Pipe
            stdout = Redirect.Pipe
        }
        assertEquals(0, proc.waitFor())
        assertEquals(CommunicateResult(0, "done\n", ""), proc.communicate(input = "ignored"))
    }

    @Test
    fun failedExecThrows() {
        assertFailsWith<ProcessException> {
            Process { arg("/nonexistent/ksubprocess-missing-executable") }
        }
        assertFailsWith<ProcessException> {
            Process { arg("/dev/null") }
        }
        assertFailsWith<ProcessException> {
            Process {
                arg("/bin/sh")
                workingDirectory = "/nonexistent/ksubprocess-missing-directory"
            }
        }
    }

    @Test
    fun signalTerminationReportsShellStyleExitCode() = runBlocking<Unit> {
        assertEquals(137, shell("kill -9 $$").waitFor())
        assertEquals(42, shell("exit 42").waitFor())

        val killed = shell("exec sleep 30")
        killed.kill()
        assertEquals(137, killed.waitFor())

        val terminated = shell("exec sleep 30")
        terminated.terminate()
        assertEquals(143, terminated.waitFor())
    }

    @Test
    fun executableIsResolvedFromPath() = runBlocking<Unit> {
        val result = Process {
            arg("sh")
            arg("-c")
            arg("echo ok")
            stdout = Redirect.Pipe
        }.communicate()
        assertEquals("ok\n", result.output)

        assertFailsWith<ProcessException> {
            Process { arg("ksubprocess-missing-executable") }
        }
    }

    private companion object {
        const val FD_SLACK = 3
    }
}
