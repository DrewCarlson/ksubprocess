/*
 * Copyright 2021 Drew Carlson
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

import platform.Foundation.*
import kotlin.native.concurrent.ThreadLocal

@Suppress("UNCHECKED_CAST")
@ThreadLocal
public actual object Environment : Map<String, String> by NSProcessInfo.processInfo.environment as Map<String, String> {

    public actual val caseInsensitive: Boolean = false
}

internal fun Map<String, String>.toEnviron() = map { "${it.key}=${it.value}" }
