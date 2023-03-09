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

/**
 * General subprocess exception.
 */
public open class ProcessException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * Indicates a process that didn't exit cleanly.
 *
 * @param result result that caused this exception
 */
public class ProcessExitException(
    public val result: CommunicateResult
) : ProcessException(
    buildString {
        append("Process exited with exit code ")
        append(result.exitCode)

        // add stderr output if any
        if (result.errors.isNotBlank()) {
            append(": ")
            val errLines = result.errors.lines()
            // check how many actual lines there are
            val errLinesNotBlank = errLines.filter { it.isNotBlank() }
            if (errLinesNotBlank.size == 1) {
                // single line, append in line
                append(errLinesNotBlank.single())
            } else {
                // append as indented lines
                for (line in errLines) {
                    append("\n    ")
                    append(line)
                }
            }
        }
    }
)

/**
 * Indicates a problem with the process setup.
 *
 * NOTE: Since some platforms only wrap other libraries, it is not guaranteed that this exception used for all config
 * errors. You might want to catch ProcessException instead.
 */
public class ProcessConfigException(
    message: String? = null,
    cause: Throwable? = null
) : ProcessException(message, cause)
