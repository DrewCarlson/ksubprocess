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
 * The current process environment.
 */
public expect object Environment : Map<String, String> {

    /** true if the environment keys are case-insensitive */
    public val caseInsensitive: Boolean
}

/**
 * Check if the given string is a valid environment key.
 */
@Suppress("unused")
internal fun Environment.validateKey(key: String) {
    require(!('=' in key || '\u0000' in key)) { "Invalid environment key: $key" }
}

/**
 * Check if the given string is a valid environment value.
 */
@Suppress("unused")
internal fun Environment.validateValue(value: String) {
    require('\u0000' !in value) { "Invalid environment value: $value" }
}

private typealias EBEntryIF = MutableMap.MutableEntry<String, String>

/**
 * A mutable copy of the current environment.
 */
public class EnvironmentBuilder(init: Map<String, String> = Environment) : AbstractMutableMap<String, String>() {

    private val backing = mutableMapOf<EnvKey, String>()

    init {
        if (init == Environment) {
            // special case on Windows: there are some dynamic env vars whose keys contain '='.
            // We skip these when adding them here.
            init.forEach { (key, value) ->
                if ('=' !in key) {
                    put(key, value)
                }
            }
        } else {
            // validate env if not copying system env.
            if (init !is EnvironmentBuilder) {
                init.forEach { (key, value) ->
                    Environment.validateKey(key)
                    Environment.validateValue(value)
                }
            }
            putAll(init)
        }
    }

    // straightforward query methods
    override fun containsKey(key: String): Boolean = backing.containsKey(EnvKey(key))

    override fun containsValue(value: String): Boolean = backing.containsValue(value)
    override fun get(key: String): String? = backing[EnvKey(key)]

    override fun remove(key: String): String? = backing.remove(EnvKey(key))

    override fun put(key: String, value: String): String? {
        Environment.validateKey(key)
        Environment.validateValue(value)
        return backing.put(EnvKey(key), value)
    }

    override val entries: MutableSet<EBEntryIF> = object : AbstractMutableSet<EBEntryIF>() {
        override val size: Int
            get() = backing.size

        override fun contains(element: EBEntryIF): Boolean = backing.entries.contains(EnvKeyEntry(element))

        override fun add(element: EBEntryIF) = put(element.key, element.value) != element.value

        override fun remove(element: EBEntryIF) = backing.entries.remove(EnvKeyEntry(element))

        override fun iterator() = object : MutableIterator<EBEntryIF> {
            val wrapped = backing.iterator()

            override fun next() = EnvEntry(wrapped.next())

            override fun hasNext() = wrapped.hasNext()
            override fun remove() = wrapped.remove()
        }
    }

    // wrapper around string that compares case-insensitive if Environment.caseInsensitive is true
    private class EnvKey(val value: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as EnvKey

            // compare case-insensitive if needed
            return value.compareTo(other.value, Environment.caseInsensitive) == 0
        }

        override fun hashCode(): Int {
            // use upper case hash code if case-insensitive
            return if (Environment.caseInsensitive) value.uppercase().hashCode() else value.hashCode()
        }
    }

    // entry of EnvironmentBuilder, wraps storage entry.
    private data class EnvEntry(val wrapped: MutableMap.MutableEntry<EnvKey, String>) : EBEntryIF {
        override val key: String
            get() = wrapped.key.value
        override val value: String
            get() = wrapped.value

        override fun setValue(newValue: String): String {
            Environment.validateValue(newValue)
            return wrapped.setValue(newValue)
        }
    }

    // storage entry type, used for lookups in entry set
    private data class EnvKeyEntry(
        override val key: EnvKey,
        override var value: String
    ) : MutableMap.MutableEntry<EnvKey, String> {
        override fun setValue(newValue: String): String = value.also {
            value = newValue
        }

        constructor(outer: EBEntryIF) : this(EnvKey(outer.key), outer.value)
    }
}
