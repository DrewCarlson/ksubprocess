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

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.js.JsName
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the [Process] class.
 */
class ProcessTests {

    @Test
    @JsName("testHelloWorld")
    fun `Run simple program`() = runTest {
        val proc = Process {
            testProgram("HelloWorldKt")
            stdout = Redirect.Pipe
        }
        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // check output
        assertEquals("Hello World!", outText.lines().firstOrNull())
    }

    @Test
    @JsName("testEcho")
    fun `Run echo program`() = runTest {
        val proc = Process {
            testProgram("EchoKt")
            stdout = Redirect.Pipe
            stdin = Redirect.Pipe
        }
        val text = """
                Line1
                Line2
                
        """.trimIndent()

        // write stdin
        proc.stdin!!.writeUtf8(text)
        proc.closeStdin()

        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // check output
        for ((ex, act) in text.lines() zip outText.lines()) {
            assertEquals(ex, act)
        }
    }

    @Test
    @JsName("testStdinFile")
    fun `Read stdin from file`() = runTest {
        val proc = Process {
            testProgram("EchoKt")
            stdout = Redirect.Pipe
            // setup stdin redirect
            stdin = Redirect.Read("testfiles/TestInput.txt")
        }
        val text = """
                    Line1
                    Line2
                    
        """.trimIndent()

        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // check output
        for ((ex, act) in text.lines() zip outText.lines()) {
            assertEquals(ex, act)
        }
    }

    @Test
    @JsName("testStdoutFile")
    fun `Write stdout to file`() = runTest {
        val outPath = "build${Path.DIRECTORY_SEPARATOR}test.output.txt".toPath()
        val proc = Process {
            testProgram("HelloWorldKt")
            stdout = Redirect.Write(outPath.toString())
        }

        // wait for termination
        proc.waitFor()

        val fileContent = CurrentFs.source(outPath).buffer().use { it.readUtf8() }
        CurrentFs.delete(outPath)

        assertEquals("Hello World!", fileContent.trimEnd())

        assertEquals(0, proc.exitCode, "Process exited normally.")
    }

    @Test
    @JsName("testArgs")
    fun `Passing arguments`() = runTest {
        val args = listOf(
            "OneArg",
            "SecondArg",
            "Arg with space"
            // TODO these don't work with on windows, even when using java. Check on linux and find a solution.
//            "ArgWithQuote\"Postfix",
//            "ArgWithQuoteAtEnd\"",
//            "Arg with space and \" in the middle",
//            "Arg with space and \"",
//            "Arg with \n newline"
        )

        val proc = Process {
            testProgram("ArgumentDumpKt")
            stdout = Redirect.Pipe

            // append args
            arguments += args
        }

        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // check output
        for ((ex, act) in args zip outText.split('#')) {
            assertEquals(ex, act)
        }
    }

    @Test
    @JsName("testEnvVars")
    fun `Setting environment variables`() = runTest {
        val customVars = mapOf(
            "Var1" to "Value1",
            "Var2" to "Value with space"
        )
        // this variable is part of the inherited environment, we remove it explicitly
        val removedVar = "TEST"

        val envKeys = customVars.keys.toList() + listOf(removedVar)

        val proc = Process {
            testProgram("EnvVarDumpKt")
            stdout = Redirect.Pipe

            // append env vars
            environment += customVars
            // remove that one var
            environment.remove(removedVar)

            // add relevant keys to args
            arguments += envKeys
        }

        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // collect env keys
        val varsFromChild = (envKeys zip outText.lines()).toMap()

        // check added vars
        for (k in customVars.keys) {
            assertEquals(customVars[k], varsFromChild[k])
        }
        // check removed var
        assertEquals("<NOT-SET>", varsFromChild[removedVar])
    }

    @Test
    @JsName("testCwd")
    fun `Changing working directory`() = runTest {
        val wd = "testfiles"

        val proc = Process {
            testProgram("PwdKt")
            stdout = Redirect.Pipe

            workingDirectory = wd
        }

        // read stdout
        val outText = proc.stdout!!.readUtf8()

        // wait for termination
        proc.waitFor()
        assertEquals(0, proc.exitCode, "Process exited normally.")

        // check result
        val actAbsWD = outText.lines().firstOrNull()
        assertTrue(actAbsWD?.endsWith(wd) ?: false, "Working directory $actAbsWD should be $wd")
    }

    @Test
    @JsName("testWaitForTimeout")
    fun `waitFor with timeout`() = runTest {
        val proc = Process {
            testProgram("SleeperKt")

            // sleep time
            arg("2")
        }
        // alive initially
        assertTrue(proc.isAlive, "Process died before first wait")
        assertNull(proc.exitCode, "Exit code should be unknown since process is alive")
        // wait for termination with timeout (waiting 1 second should fail since the sleeper program takes 2)
        assertNull(proc.waitFor(1.seconds), "Should not succeed yet")
        assertTrue(proc.isAlive, "Process died after first wait")
        assertNull(proc.exitCode, "Exit code should be unknown since process is still alive")

        // wait another 3 seconds max - this should work
        assertEquals(0, proc.waitFor(2.seconds), "Should succeed since the subprocess takes less time")
        assertFalse(proc.isAlive, "Process should now be stopped")
        assertEquals(0, proc.exitCode, "Process should have exited normally")
    }

    @Test
    @JsName("testExitCode")
    fun `Process exit code`() = runTest {
        val codes = listOf(0, 1, 120)

        for (code in codes) {
            val proc = Process {
                testProgram("ExitCodeKt")

                arg(code.toString())
            }
            val codeAct = proc.waitFor()

            assertEquals(code, codeAct, "Process exited with desired code.")
        }
    }
}
