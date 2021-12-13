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

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [Environment] and [EnvironmentBuilder]
 */
class EnvironmentTests {

    @Test
    @JsName("testEnvRead")
    fun `Environment contains explicitly set variable`() {
        // we set a variable TEST=TESTVAL
        assertTrue("TEST" in Environment, "TEST is in environment keys")
        assertTrue("TEST" in Environment.keys, "TEST is in environment keys")
        assertTrue("TESTVAL" in Environment.values, "TESTVAL is in environment values")
        assertTrue(Environment.any { it.key == "TEST" && it.value == "TESTVAL" }, "TEST is in environment values")
        assertEquals("TESTVAL", Environment["TEST"])
    }

    @Test
    @JsName("testEnvBuilderCreate")
    fun `EnvironmentBuilder initialized on existing environment`() {
        val eb = EnvironmentBuilder()
        assertEquals(Environment.filter { (k, _) -> '=' !in k }, eb.toMap())
    }
}