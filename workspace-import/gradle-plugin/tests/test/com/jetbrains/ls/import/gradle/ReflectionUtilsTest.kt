// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.import.gradle

import com.jetbrains.ls.imports.gradle.utils.param
import com.jetbrains.ls.imports.gradle.utils.reflected
import com.jetbrains.ls.imports.gradle.utils.staticReflected
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private interface IReflectionUtilsTest {
    fun method(): String
    fun method(int: Int): Int
    fun method(value: String): String
    fun method(value: Any): String
}

internal class ReflectionUtilsTest : IReflectionUtilsTest {

    companion object {
        @JvmStatic
        fun static() = "foo"

        @JvmStatic
        fun static(int: Int) = int

        @JvmStatic
        fun static(value: String) = "str: $value"

        @JvmStatic
        fun static(value: Any) = "obj: $value"
    }

    override fun method() = "foo"
    override fun method(int: Int) = int
    override fun method(value: String) = "str: $value"
    override fun method(value: Any) = "obj: $value"

    @Test
    fun `test - static call`() {
        val reflected = staticReflected<ReflectionUtilsTest>()
        assertEquals("foo", reflected.call("static")?.unwrapAs<String>())
        assertEquals("str: foo", reflected.call("static", param<String>("foo"))?.unwrapAs<String>())
        assertEquals("str: foo", reflected.call("static", param("foo"))?.unwrapAs<String>())
        assertEquals("obj: foo", reflected.call("static", param<Any>("foo"))?.unwrapAs<String>())
        assertEquals(42, reflected.call("static", param(42))?.unwrapAs<Int>())
    }

    @Test
    fun `test - method call`() {
        val reflected = this.reflected
        assertEquals("foo", reflected.call("method")?.unwrapAs<String>())
        assertEquals("str: foo", reflected.call("method", param<String>("foo"))?.unwrapAs<String>())
        assertEquals("str: foo", reflected.call("method", param("foo"))?.unwrapAs<String>())
        assertEquals("obj: foo", reflected.call("method", param<Any>("foo"))?.unwrapAs<String>())
        assertEquals(42, reflected.call("method", param(42))?.unwrapAs<Int>())
    }
}
