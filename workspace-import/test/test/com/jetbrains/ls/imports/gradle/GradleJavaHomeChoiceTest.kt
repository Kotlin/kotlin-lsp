// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.gradleVersionOfDistribution
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.javaHomeForGradle
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `JAVA_HOME` drives the Gradle import when the wrapper's Gradle version can run on it, see LSP-882. */
class GradleJavaHomeChoiceTest {
    private val javaHome = "/jdks/corretto-17"
    private val gradle811 = GradleVersion.version("8.11")
    private val jdks = mapOf(javaHome to JavaVersion.compose(17), "/jdks/jdk-26" to JavaVersion.compose(26))
    private val versionOf: (String) -> JavaVersion? = { jdks[it] }

    @Test
    fun `a compatible JAVA_HOME wins`() {
        assertEquals(javaHome, javaHomeForGradle(javaHome, gradle811, versionOf))
    }

    @Test
    fun `an incompatible JAVA_HOME is skipped`() {
        assertNull(javaHomeForGradle("/jdks/jdk-26", gradle811, versionOf))
    }

    @Test
    fun `a path that is not a JDK is skipped`() {
        assertNull(javaHomeForGradle("/not/a/jdk", gradle811, versionOf))
    }

    @Test
    fun `a missing or blank JAVA_HOME gives nothing`() {
        assertNull(javaHomeForGradle(null, gradle811, versionOf))
        assertNull(javaHomeForGradle(" ", gradle811, versionOf))
    }

    @Test
    fun `a wrapper distribution URL names its Gradle release`() {
        assertEquals(gradle811, gradleVersionOfDistribution("https://services.gradle.org/distributions/gradle-8.11-bin.zip"))
        assertEquals(GradleVersion.version("8.11-rc-1"), gradleVersionOfDistribution("https://services.gradle.org/distributions/gradle-8.11-rc-1-all.zip"))
    }

    @Test
    fun `a custom wrapper distribution URL names no Gradle release`() {
        // The version is unknown, so JAVA_HOME must not be checked against the Tooling API's own version.
        assertNull(gradleVersionOfDistribution("https://mirror.example.com/8.11/gradle.zip"))
        assertNull(gradleVersionOfDistribution("https://mirror.example.com/gradle-custom.zip"))
        assertNull(gradleVersionOfDistribution("not a url"))
    }
}
