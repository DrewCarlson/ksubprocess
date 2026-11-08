package ksubprocess

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.getpid
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ReapTests {

    private fun zombieChildren(): Int {
        val self = getpid().toString()
        return SystemFileSystem.list(Path("/proc")).count { dir ->
            if (dir.name.any { !it.isDigit() }) return@count false
            val stat = runCatching {
                SystemFileSystem.source(Path(dir, "stat")).buffered().use { it.readString() }
            }.getOrNull() ?: return@count false
            val fields = stat.substringAfterLast(')').trim().split(' ')
            fields[0] == "Z" && fields[1] == self
        }
    }

    // not inline, so no reference to the processes survives on the caller's stack
    private fun startAndDrop(script: String) {
        repeat(20) { Process { arg("/bin/sh"); arg("-c"); arg(script) } }
    }

    @OptIn(NativeRuntimeApi::class)
    private suspend fun collect() {
        GC.collect()
        delay(500)
    }

    @Test
    fun abandonedProcessesAreReaped() = runBlocking {
        startAndDrop("exit 0")
        delay(300)
        collect()
        assertEquals(0, zombieChildren())
    }

    @Test
    fun processesAbandonedWhileRunningAreReapedLater() = runBlocking {
        startAndDrop("sleep 0.3")
        collect()
        delay(500)
        Process { arg("/bin/sh"); arg("-c"); arg("exit 0") }.waitFor()
        assertEquals(0, zombieChildren())
    }
}
