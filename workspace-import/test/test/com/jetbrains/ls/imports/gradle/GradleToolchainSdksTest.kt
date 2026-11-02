// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.json.DependencyData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A Gradle toolchain JDK gets its own SDK, one per JDK home. */
class GradleToolchainSdksTest {
    @TempDir
    lateinit var dir: Path

    private fun jdk(name: String): String = Files.createDirectories(dir.resolve(name)).toString()

    @Test
    fun `the home of the current SDK gives no dependency`() {
        val sdks = GradleToolchainSdks()
        val home = jdk("jdk-17")

        assertNull(sdks.dependencyFor(home, 17, home))
        assertEquals(emptyList<Any>(), sdks.sdks)
    }

    @Test
    fun `a new home gets a toolchain SDK`() {
        val sdks = GradleToolchainSdks()
        val home = jdk("jdk-21")

        assertEquals(DependencyData.Sdk("21 (toolchain)", "JavaSDK"), sdks.dependencyFor(home, 21, jdk("jdk-17")))
        assertEquals(DependencyData.Sdk("21 (toolchain)", "JavaSDK"), sdks.dependencyFor(home, 21, null))
        val sdk = sdks.sdks.single()
        assertEquals("JDK_21", sdk.version)
        assertEquals(home, sdk.homePath)
    }

    @Test
    fun `a symlinked home shares the SDK`() {
        val home = jdk("jdk-21")
        val link = dir.resolve("link-21")
        try {
            Files.createSymbolicLink(link, Path.of(home))
        } catch (e: Exception) {
            assumeTrue(false, "Cannot create a symlink: ${e.message}")
        }
        val sdks = GradleToolchainSdks()

        assertEquals(sdks.dependencyFor(home, 21, null), sdks.dependencyFor(link.toString(), 21, null))
        assertEquals(1, sdks.sdks.size)
        assertNull(sdks.dependencyFor(link.toString(), 21, home))
    }

    @Test
    fun `two homes of one version get unique names`() {
        val sdks = GradleToolchainSdks()

        assertEquals(DependencyData.Sdk("21 (toolchain)", "JavaSDK"), sdks.dependencyFor(jdk("a/jdk-21"), 21, null))
        assertEquals(DependencyData.Sdk("21 (toolchain) (2)", "JavaSDK"), sdks.dependencyFor(jdk("b/jdk-21"), 21, null))
        assertEquals(DependencyData.Sdk("21 (toolchain) (3)", "JavaSDK"), sdks.dependencyFor(jdk("c/jdk-21"), 21, null))
        assertEquals(listOf("21 (toolchain)", "21 (toolchain) (2)", "21 (toolchain) (3)"), sdks.sdks.map { it.name })
    }
}
