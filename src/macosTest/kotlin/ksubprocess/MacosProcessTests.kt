package ksubprocess

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MacosProcessTests {

    @Test
    fun missingAbsoluteExecutableThrows() {
        assertFailsWith<ProcessException> {
            Process { arg("/nonexistent/ksubprocess-missing-executable") }
        }
    }

    @Test
    fun missingWorkingDirectoryThrows() {
        assertFailsWith<ProcessException> {
            Process {
                arg("/bin/sh")
                workingDirectory = "/nonexistent/ksubprocess-missing-directory"
            }
        }
    }
}
