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

import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.invoke
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the [exec] function.
 *
 * Also implicitly tests [communicate], since exec itself doesn't do much.
 */
class ExecTests {

    @Test
    @JsName("testHelloWorld")
    fun `Run simple program`() = runTest {
        val result = Default {
            exec {
                testProgram("HelloWorldKt")
            }
        }

        // check output
        assertEquals("Hello World!", result.output.lines().firstOrNull())
    }

    @Test
    @JsName("testEcho")
    fun `Run echo program`() = runTest {
        val text = """
                Line1
                Line2
                
        """.trimIndent()

        val result = Default {
            exec {
                testProgram("EchoKt")
                // setup input
                input = text
            }
        }

        // check output
        for ((ex, act) in text.lines() zip result.output.lines()) {
            assertEquals(ex, act)
        }
    }

    @Test
    @JsName("testStdinFile")
    fun `Read stdin from file`() = runTest {
        val result = Default {
            exec {
                testProgram("EchoKt")
                // setup stdin redirect
                stdin = Redirect.Read("testfiles/TestInput.txt")
            }
        }

        val text = """
                    Line1
                    Line2
                    
        """.trimIndent()
        // check output
        for ((ex, act) in text.lines() zip result.output.lines()) {
            assertEquals(ex, act)
        }
    }

    @Test
    @JsName("testExitCode")
    fun `Process exit code and check=true`() = runTest {
        val codes = listOf(0, 1, 120)

        for (code in codes) {
            val result = Default {
                exec {
                    testProgram("ExitCodeKt")

                    // setup return code
                    arg(code.toString())

                    // disable check so we can verify the code manually
                    check = false
                }
            }

            assertEquals(code, result.exitCode, "Process exited with desired code.")
        }

        // verify check function
        assertFailsWith(ProcessExitException::class, "Check trips process exit") {
            Default {
                exec {
                    testProgram("ExitCodeKt")

                    // setup return code
                    arg("1")

                    // explicitly enable check
                    check = true
                }
            }
        }
    }

    @Test
    @JsName("testTimeout")
    fun `Timeout and termination`() = runTest {
        // time run duration
        val (_, time) = measureTimedValue {
            Default {
                exec {
                    testProgram("SleeperKt")

                    // setup 20 seconds sleep
                    arg("20")

                    // timeout way earlier with grace
                    timeout = 2.seconds

                    // disable check so we can verify the code manually
                    check = false
                }
            }
        }

        // assertions on timing are always wonky, but we can probably guarantee this
        assertTrue(time < 3.seconds, "Timeout worked.")

        // NOTE: cannot check return codes, since those are platform dependent
        // NOTE: cannot test the forceful termination generally, since it's platform dependent.
    }
}
