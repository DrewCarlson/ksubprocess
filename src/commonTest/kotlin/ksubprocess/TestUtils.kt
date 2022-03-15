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

import ksubprocess.Environment
import ksubprocess.ExecArgumentsBuilder
import ksubprocess.ProcessArgumentBuilder
import ksubprocess.Redirect
import kotlin.test.fail


/**
 * Setup process arguments to run a test program from the testprograms subproject.
 *
 * Also sets up streams if we're not a [exec] builder. Output streams are inherited to pass them on to the test
 * report if no special interest is taken. Stdin is set to Null since there will never be input. When using [exec],
 * input and output are handled automatically, so there is no issue.
 */
fun ProcessArgumentBuilder.testProgram(mainClass: String) {
    // add java launch args
    arguments.clear()
    arg(ksubprocess.Environment["PT_JAVA_EXE"] ?: fail("Missing PT_JAVA_EXE environment variable"))
    arg("-cp")
    arg(ksubprocess.Environment["PT_JAR"] ?: fail("Missing PT_JAR environment variable"))
    arg(mainClass)

    // setup streams if needed.
    if (this !is ExecArgumentsBuilder) {
        // initialize redirect outputs to inherit so the parent sees them
        stderr = Redirect.Inherit
        stdout = Redirect.Inherit
        // input is empty by default
        stdin = Redirect.Null
    } else {
        // setup checking, which in tests is usually on
        check = true
    }
}
