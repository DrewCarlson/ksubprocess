package ksubprocess

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmExitCodeTests {

    @Test
    fun signalTerminationReportsShellStyleExitCode() = runBlocking<Unit> {
        if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val killed = Process { arg("/bin/sh"); arg("-c"); arg("exec sleep 30") }
        killed.kill()
        assertEquals(137, killed.waitFor())

        val terminated = Process { arg("/bin/sh"); arg("-c"); arg("exec sleep 30") }
        terminated.terminate()
        assertEquals(143, terminated.waitFor())
    }
}
