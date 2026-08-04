// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.api.WorkspaceImportOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Covers the environment defaults + `${'$'}{env.VAR}` macro substitution that the Maven importer relies on:
 * it starts the build with a cleared environment, so `PATH` (and anything else the child needs) has to come
 * through [WorkspaceImportOptions.withResolvedEnvironment].
 */
class WorkspaceImportOptionsEnvTest {
    private val systemEnv = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/me")

    @Test
    fun `default PATH is taken from the environment`() {
        val resolved = WorkspaceImportOptions.EMPTY.withResolvedEnvironment(systemEnv)
        assertEquals("/usr/bin:/bin", resolved.environment["PATH"])
    }

    @Test
    fun `default PATH is resolved from a Windows-style lowercase Path key`() {
        // On Windows System.getenv() exposes the variable as `Path`; the `${'$'}{env.PATH}` default must still resolve.
        val windowsEnv = mapOf("Path" to "C:\\Windows;C:\\Windows\\System32")
        val resolved = WorkspaceImportOptions.EMPTY.withResolvedEnvironment(windowsEnv)
        assertEquals("C:\\Windows;C:\\Windows\\System32", resolved.environment["PATH"])
    }

    @Test
    fun `client PATH in different case is not shadowed by the default`() {
        // Client provides `Path`; the default `PATH` must not be added alongside it (would collide on Windows).
        val options = WorkspaceImportOptions(environment = mapOf("Path" to "/custom/bin"))
        val resolved = options.withResolvedEnvironment(systemEnv)
        assertEquals("/custom/bin", resolved.environment["Path"])
        assertFalse("PATH" in resolved.environment)
    }

    @Test
    fun `client env value overrides the default`() {
        val options = WorkspaceImportOptions(environment = mapOf("PATH" to "/custom/bin"))
        val resolved = options.withResolvedEnvironment(systemEnv)
        assertEquals("/custom/bin", resolved.environment["PATH"])
    }

    @Test
    fun `env macros are expanded inside client values`() {
        val options = WorkspaceImportOptions(environment = mapOf("MY_PATH" to "\${env.PATH}:/opt/tool"))
        val resolved = options.withResolvedEnvironment(systemEnv)
        assertEquals("/usr/bin:/bin:/opt/tool", resolved.environment["MY_PATH"])
    }

    @Test
    fun `unknown env macros expand to empty and do not leak`() {
        // The analyzer's own JAVA_TOOL_OPTIONS must never leak into a JDK8 Maven JVM: it is only present if a
        // client explicitly asks for it, and an unknown macro resolves to empty rather than the ambient value.
        val options = WorkspaceImportOptions(environment = mapOf("MAVEN_OPTS" to "\${env.JAVA_TOOL_OPTIONS}"))
        val resolved = options.withResolvedEnvironment(systemEnv)
        assertEquals("", resolved.environment["MAVEN_OPTS"])
        assertFalse("JAVA_TOOL_OPTIONS" in resolved.environment)
    }

    @Test
    fun `present default vars are injected and absent ones are dropped`() {
        // Unix-style env: HOME/TMPDIR present, Windows-only SystemRoot absent -> must not leak as an empty var.
        val unixEnv = mapOf("PATH" to "/usr/bin", "HOME" to "/home/me", "TMPDIR" to "/tmp", "LANG" to "en_US.UTF-8")
        val resolved = WorkspaceImportOptions.EMPTY.withResolvedEnvironment(unixEnv)
        assertEquals("/home/me", resolved.environment["HOME"])
        assertEquals("/tmp", resolved.environment["TMPDIR"])
        assertEquals("en_US.UTF-8", resolved.environment["LANG"])
        assertFalse("SystemRoot" in resolved.environment)
        assertFalse("TEMP" in resolved.environment)
    }

    @Test
    fun `windows temp vars are injected from the environment`() {
        val windowsEnv = mapOf("Path" to "C:\\Windows", "TEMP" to "C:\\Temp", "TMP" to "C:\\Temp", "SystemRoot" to "C:\\Windows")
        val resolved = WorkspaceImportOptions.EMPTY.withResolvedEnvironment(windowsEnv)
        assertEquals("C:\\Temp", resolved.environment["TEMP"])
        assertEquals("C:\\Temp", resolved.environment["TMP"])
        assertEquals("C:\\Windows", resolved.environment["SystemRoot"])
        assertFalse("TMPDIR" in resolved.environment)
    }

    @Test
    fun `other option fields are preserved`() {
        val options = WorkspaceImportOptions(
            environment = mapOf("K" to "v"),
            systemProperties = mapOf("p" to "1"),
            javaHome = Path.of("/jdk"),
            projectPath = "sub/pom.xml",
        )
        val resolved = options.withResolvedEnvironment(systemEnv)
        assertEquals(mapOf("p" to "1"), resolved.systemProperties)
        assertEquals(Path.of("/jdk"), resolved.javaHome)
        assertEquals("sub/pom.xml", resolved.projectPath)
    }
}
