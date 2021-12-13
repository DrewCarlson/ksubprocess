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
package drewcarlson.ksubprocess

import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import java.lang.Process as JProcess

actual class Process actual constructor(actual val args: ProcessArguments) {

    companion object {
        @JvmStatic
        private val NULL_FILE = File(
            if (System.getProperty("os.name").startsWith("Windows")) "NUL"
            else "/dev/null"
        )

        // converts Redirect to ProcessBuilder.Redirect
        // note: Does not handle stderr = Stdout, since that's a different method in process builder.
        @JvmStatic
        private fun Redirect.toJava(stream: String): ProcessBuilder.Redirect = when (this) {
            Redirect.Null ->
                if (stream == "stdin") ProcessBuilder.Redirect.from(NULL_FILE)
                else ProcessBuilder.Redirect.to(NULL_FILE)
            Redirect.Inherit -> ProcessBuilder.Redirect.INHERIT
            Redirect.Pipe -> ProcessBuilder.Redirect.PIPE
            is Redirect.Read -> ProcessBuilder.Redirect.from(File(file))
            is Redirect.Write ->
                if (append) ProcessBuilder.Redirect.appendTo(File(file))
                else ProcessBuilder.Redirect.to(File(file))
            Redirect.Stdout -> throw IllegalStateException("Redirect.Stdout must be handled separately.")
        }
    }


    private val impl: JProcess

    init {
        try {
            // convert args to java process builder
            val pb = ProcessBuilder()
            pb.command(args.arguments)
            args.workingDirectory?.let { pb.directory(File(it)) }
            if (args.environment != null) {
                // need to fully planate env, there is no better way
                pb.environment().clear()
                pb.environment().putAll(args.environment)
            }
            pb.redirectInput(args.stdin.toJava("stdin"))
            pb.redirectOutput(args.stdout.toJava("stdout"))
            if (args.stderr == Redirect.Stdout)
                pb.redirectErrorStream(true)
            else
                pb.redirectError(args.stderr.toJava("stderr"))

            // start process
            impl = pb.start()
        } catch (e: IOException) {
            // unfortunately we can't quite detect config errors here
            throw ProcessException(cause = e)
        }
    }

    actual val isAlive: Boolean
        get() = impl.isAlive
    actual val exitCode: Int?
        get() = try {
            impl.exitValue()
        } catch (e: IllegalThreadStateException) {
            null
        }

    actual fun waitFor(): Int {
        return impl.waitFor()
    }

    actual fun waitFor(timeout: Duration): Int? {
        // perform wait
        val terminated = impl.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        // return exit code as if successful
        return if (terminated) impl.exitValue() else null
    }

    actual fun terminate() {
        impl.destroy()
    }

    actual fun kill() {
        impl.destroyForcibly()
    }

    actual val stdin: Output? by lazy {
        if (args.stdin == Redirect.Pipe) impl.outputStream.asOutput()
        else null
    }

    actual val stdout: Input? by lazy {
        if (args.stdout == Redirect.Pipe) impl.inputStream.asInput()
        else null
    }

    actual val stderr: Input? by lazy {
        if (args.stderr == Redirect.Pipe) impl.errorStream.asInput()
        else null
    }

}
