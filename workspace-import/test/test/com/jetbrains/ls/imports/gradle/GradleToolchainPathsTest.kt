// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.toolchainPathsArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Gradle toolchains get the JDKs the server finds, merged with the user's own `org.gradle.java.installations.paths`. */
class GradleToolchainPathsTest {
    private val prefix = "-Porg.gradle.java.installations.paths="
    private val jdk17 = "/jdks/jdk-17"
    private val jdk21 = "/jdks/jdk-21"
    private val ownJdk = "/opt/own-jdk"

    @Test
    fun `the server JDKs alone are passed`() {
        assertEquals("$prefix$jdk17,$jdk21", toolchainPathsArgument(null, null, listOf(jdk17, jdk21)))
    }

    @Test
    fun `own paths come first and duplicates are dropped`() {
        assertEquals(
            "$prefix$ownJdk,$jdk17,$jdk21",
            toolchainPathsArgument("$ownJdk, $jdk17", "true", listOf(jdk17, jdk21, jdk21)),
        )
    }

    @Test
    fun `auto-detect off passes nothing`() {
        assertNull(toolchainPathsArgument(null, "false", listOf(jdk17)))
        assertNull(toolchainPathsArgument(ownJdk, " FALSE ", listOf(jdk17)))
    }

    @Test
    fun `nothing new passes nothing`() {
        assertNull(toolchainPathsArgument(null, null, emptyList()))
        assertNull(toolchainPathsArgument("$jdk17,$jdk21", null, listOf(jdk21, jdk17)))
    }

    @Test
    fun `a server JDK path with a comma is dropped`() {
        assertEquals("$prefix$jdk17", toolchainPathsArgument(null, null, listOf("/jdks/a,b", jdk17)))
        assertNull(toolchainPathsArgument(null, null, listOf("/jdks/a,b")))
    }

    @Test
    fun `blank own entries are ignored`() {
        assertEquals("$prefix$ownJdk,$jdk17", toolchainPathsArgument(" , $ownJdk,, ", null, listOf(jdk17)))
        assertEquals("$prefix$jdk17", toolchainPathsArgument("   ", null, listOf(jdk17)))
    }
}
