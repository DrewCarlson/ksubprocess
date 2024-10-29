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

// this is just a thin wrapper around System.getenv()
public actual object Environment : AbstractMap<String, String>(), Map<String, String> {

    actual override fun get(key: String): String? = System.getenv(key)
    actual override fun containsKey(key: String): Boolean = System.getenv(key) != null

    actual override val entries: Set<Map.Entry<String, String>>
        get() = System.getenv().entries

    public actual val caseInsensitive: Boolean by lazy {
        val osName = System.getProperty("os.name")
        osName.startsWith("Windows")
    }
}
