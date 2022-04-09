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

import platform.posix.F_OK
import platform.posix.X_OK
import platform.posix.access

val executablePaths: List<String> by lazy {
    Environment["PATH"]?.split(':') ?: listOf()
}

/**
 * Locate the named executable on the system PATH.
 */
fun findExecutable(name: String): String? =
    executablePaths.map { "$it/$name" }.first { access(it, F_OK or X_OK) == 0 }
