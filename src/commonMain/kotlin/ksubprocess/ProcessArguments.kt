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
 * Subprocess pipe redirection settings.
 */
sealed class Redirect {

    /**
     * Discard output, no input.
     */
    object Null : Redirect() {
        override fun toString() = "discard"
    }

    /**
     * Create a pipe.
     */
    object Pipe : Redirect() {
        override fun toString() = "pipe"
    }

    /**
     * Inherit from parent process.
     */
    object Inherit : Redirect() {
        override fun toString() = "inherit"
    }

    /**
     * Merge stderr into stdout.
     */
    object Stdout : Redirect() {
        override fun toString() = "stdout"
    }

    /**
     * Read stdin from file.
     *
     * @param file name of input file
     */
    class Read(val file: String) : Redirect() {
        override fun toString() = "read from $file"
    }

    /**
     * Write stdout or stderr to file.
     *
     * @param file name of output file
     * @param append true to keep existing file contents
     */
    class Write(val file: String, val append: Boolean = false) : Redirect() {
        override fun toString() = buildString {
            if (append)
                append("append to ")
            else
                append("write to ")
            append(file)
        }
    }
}


/**
 * Mutable builder for process arguments and environment.
 *
 * This class is open so [ExecArgumentsBuilder] can inherit from it and add it's own arguments.
 */
open class ProcessArgumentBuilder {

    /** Command line, including executable as first element. */
    val arguments: MutableList<String> = mutableListOf()

    /** Subprocess working directory or null to inherit from parent. */
    var workingDirectory: String? = null

    // environment is only set if changed.
    private var environmentOrNull: MutableMap<String, String>? = null

    /**
     * Subprocess environment.
     *
     * Will be initialized to the current process environment. Call `environment.clear()` to override the environment
     * completely. This design was chosen because inheriting the parent environment is more common then overriding
     * it completely.
     */
    val environment: MutableMap<String, String>
        get() = environmentOrNull ?: EnvironmentBuilder().also { environmentOrNull = it }
    /**
     * Check if environment was modified at all.
     *
     * This is not a guarantee that there are changes, it can actually only check if [environment] was accessed.
     */
    val isEnvironmentModified: Boolean
        get() = environmentOrNull != null

    /** stdin redirection, defaults to Pipe. */
    var stdin: Redirect = Redirect.Pipe
        set(value) {
            require(!(value is Redirect.Write || value is Redirect.Stdout)) { "Unsupported redirect for stdin: $value" }
            field = value
        }
    /** stdout redirection, defaults to Pipe. */
    var stdout: Redirect = Redirect.Pipe
        set(value) {
            require(!(value is Redirect.Read || value is Redirect.Stdout)) { "Unsupported redirect for stdout: $value" }
            field = value
        }
    /** stderr redirection, defaults to Pipe. */
    var stderr: Redirect = Redirect.Pipe
        set(value) {
            require(value !is Redirect.Read) { "Unsupported redirect for stderr: $value" }
            field = value
        }

    // convenience builder functions
    /**
     * Append one argument to command line.
     *
     * @param arg added argument
     */
    fun arg(arg: String) {
        arguments.add(arg)
    }

    /**
     * Read stdin from file.
     *
     * @param file name of input file
     */
    fun stdin(file: String) {
        stdin = Redirect.Read(file)
    }

    /**
     * Write stdout to file.
     *
     * @param file name of output file
     * @param append true to keep existing file contents
     */
    fun stdout(file: String, append: Boolean = false) {
        stdout = Redirect.Write(file, append)
    }

    /**
     * Write stderr to file.
     *
     * @param file name of output file
     * @param append true to keep existing file contents
     */
    fun stderr(file: String, append: Boolean = false) {
        stderr = Redirect.Write(file, append)
    }

    /**
     * Create [ProcessArguments] from builder.
     */
    fun build() = ProcessArguments(
        arguments,
        workingDirectory,
        environmentOrNull,
        stdin,
        stdout,
        stderr
    )

}

/**
 * Immutable process launch arguments.
 *
 * @param arguments command line, including executable as first element
 * @param workingDirectory subprocess working directory or null to inherit from parent
 * @param environment subprocess environment or null to inherit from parent.
 *                    NOTE: Setting this will override the parent environment
 * @param stdin stdin redirection, defaults to Pipe
 * @param stdout stdout redirection, defaults to Pipe
 * @param stderr stderr redirection, defaults to Pipe
 */
class ProcessArguments(
    arguments: Iterable<String>,
    val workingDirectory: String? = null,
    environment: Map<String, String>? = null,
    val stdin: Redirect = Redirect.Pipe,
    val stdout: Redirect = Redirect.Pipe,
    val stderr: Redirect = Redirect.Pipe
) {

    val arguments = arguments.toList()
    val environment: Map<String, String>? = environment?.let { EnvironmentBuilder(it) }

    init {
        // validate arguments
        require(this.arguments.isNotEmpty()) { "The argument list must have at least one element!" }
        // environment is validated by EnvironmentBuilder constructor
    }

    /**
     * Create with vararg arguments list.
     *
     * @param arguments command line, including executable as first element
     * @param workingDirectory subprocess working directory or null to inherit from parent
     * @param environment subprocess environment or null to inherit from parent.
     *                    NOTE: Setting this will override the parent environment
     * @param stdin stdin redirection, defaults to Pipe
     * @param stdout stdout redirection, defaults to Pipe
     * @param stderr stderr redirection, defaults to Pipe
     */
    constructor(
        vararg arguments: String,
        workingDirectory: String? = null,
        environment: Map<String, String>? = null,
        stdin: Redirect = Redirect.Pipe,
        stdout: Redirect = Redirect.Pipe,
        stderr: Redirect = Redirect.Pipe
    ) : this(arguments.asIterable(), workingDirectory, environment, stdin, stdout, stderr)
}

/**
 * Create [ProcessArguments] using builder.
 *
 * @param builder builder callback
 */
@Suppress("FunctionName")
inline fun ProcessArguments(builder: ProcessArgumentBuilder.() -> Unit) =
    ProcessArgumentBuilder().apply(builder).build()

