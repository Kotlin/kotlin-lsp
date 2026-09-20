// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.findWrapperProperties
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.gradleBuildRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

/**
 * The wrapper is read from the build root the Gradle connector resolves, the nearest directory with a settings
 * script, so importing a subproject sees the root's wrapper version, see LSP-882. `buildSrc` and declarative
 * `settings.gradle.dcl` follow Gradle's own rules.
 */
class GradleWrapperDiscoveryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun build(settingsScript: String, wrapper: Boolean): Path {
        val root = tempDir.resolve("build").createDirectories()
        root.resolve(settingsScript).createFile()
        root.resolve("app").createDirectories().resolve("build.gradle.kts").createFile()
        if (wrapper) {
            root.resolve("gradle/wrapper").createDirectories().resolve("gradle-wrapper.properties")
                .writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11-bin.zip\n")
        }
        return root
    }

    @Test
    fun `a subproject reads the wrapper of its build root`() {
        val root = build("settings.gradle.kts", wrapper = true)
        assertEquals(root, gradleBuildRoot(root.resolve("app")))
        assertEquals(root.resolve("gradle/wrapper/gradle-wrapper.properties"), findWrapperProperties(root.resolve("app")))
    }

    @Test
    fun `the build root itself keeps its wrapper`() {
        val root = build("settings.gradle", wrapper = true)
        assertEquals(root, gradleBuildRoot(root))
        assertEquals(root.resolve("gradle/wrapper/gradle-wrapper.properties"), findWrapperProperties(root))
    }

    @Test
    fun `a build script path resolves to its directory's build`() {
        val root = build("settings.gradle.kts", wrapper = true)
        assertEquals(root, gradleBuildRoot(root.resolve("app/build.gradle.kts")))
    }

    @Test
    fun `a build without a wrapper has none for its subprojects either`() {
        val root = build("settings.gradle.kts", wrapper = false)
        assertNull(findWrapperProperties(root.resolve("app")))
    }

    @Test
    fun `buildSrc is its own build root and never inherits the parent wrapper`() {
        // Gradle disables the upward search for buildSrc: without its own wrapper it runs on the Tooling API version.
        val root = build("settings.gradle.kts", wrapper = true)
        val buildSrc = root.resolve("buildSrc").createDirectories()
        buildSrc.resolve("build.gradle.kts").createFile()
        assertEquals(buildSrc, gradleBuildRoot(buildSrc))
        assertNull(findWrapperProperties(buildSrc))
    }

    @Test
    fun `a declarative settings script marks a build root`() {
        val root = build("settings.gradle.kts", wrapper = true)
        val nested = root.resolve("nested").createDirectories()
        nested.resolve("settings.gradle.dcl").createFile()
        assertEquals(nested, gradleBuildRoot(nested))
        assertNull(findWrapperProperties(nested))
    }

    @Test
    fun `only the wrapper properties file the connector reads counts`() {
        val root = build("settings.gradle.kts", wrapper = false)
        root.resolve("gradle/wrapper").createDirectories().resolve("other.properties").createFile()
        assertNull(findWrapperProperties(root))
    }

    @Test
    fun `a directory without a settings script is its own build root`() {
        val lonely = tempDir.resolve("lonely").createDirectories()
        assertEquals(lonely, gradleBuildRoot(lonely))
        assertNull(findWrapperProperties(lonely))
    }
}
