// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.imports.maven.mavenArgs
import com.jetbrains.ls.imports.maven.mavenJavaArgs
import com.jetbrains.ls.imports.maven.surefireArgLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class MavenRunCommandsTest {
    private val app = BuildUnit(projectPath = "app")

    @Test
    fun `build of one module compiles it and the modules it depends on`() {
        assertEquals(listOf("-pl", "app", "-am", "compile"), mavenArgs(RunTask.Build(app), RunOptions()))
        assertEquals(listOf("-pl", "app", "-am", "test-compile"), mavenArgs(RunTask.Build(app, testScope = true), RunOptions()))
    }

    @Test
    fun `build of the whole reactor compiles the tests too and fails at the end`() {
        assertEquals(listOf("--fail-at-end", "test-compile"), mavenArgs(RunTask.Build(), RunOptions()))
    }

    @Test
    fun `run compiles and writes the runtime classpath, tool arguments last`() {
        val args = mavenArgs(RunTask.Run(app, "com.acme.Main"), RunOptions(toolArgs = listOf("-o")), classpathFile = Path.of("/tmp/cp.txt"))
        assertEquals(
            listOf("-pl", "app", "-am", "compile", "dependency:build-classpath", "-Dmdep.includeScope=runtime", "-Dmdep.outputFile=${Path.of("/tmp/cp.txt")}", "-o"),
            args,
        )
    }

    @Test
    fun `java runs the entry on the module output and the written classpath`() {
        val root = Path.of("/work/project")
        val args = mavenJavaArgs(
            java = Path.of("/jdk/bin/java"),
            root = root,
            task = RunTask.Run(app, "com.acme.Main"),
            options = RunOptions(programArgs = listOf("--port", "8080")),
            vmArgs = listOf("-Xmx1g"),
            classpath = "/m2/a.jar${File.pathSeparator}/m2/b.jar\n",
        )
        val classes = root.resolve("app").resolve("target").resolve("classes").toString()
        assertEquals(
            listOf("/jdk/bin/java", "-Xmx1g", "-cp", "$classes${File.pathSeparator}/m2/a.jar${File.pathSeparator}/m2/b.jar", "com.acme.Main", "--port", "8080"),
            args,
        )
        val modular = mavenJavaArgs(Path.of("/jdk/bin/java"), root, RunTask.Run(app, "acme.app/com.acme.Main"), RunOptions(), emptyList(), "")
        assertEquals(listOf("/jdk/bin/java", "--module-path", classes, "-m", "acme.app/com.acme.Main"), modular)
    }

    @Test
    fun `test names the tests and does not fail a module without them`() {
        val args = mavenArgs(RunTask.Test(app, listOf("com.acme.ATest", "com.acme.BTest#m")), RunOptions(), argLine = "-Xmx1g")
        assertEquals(
            listOf("-pl", "app", "-am", "test", "-Dtest=com.acme.ATest,com.acme.BTest#m", "-Dsurefire.failIfNoSpecifiedTests=false", "-DargLine=-Xmx1g"),
            args,
        )
    }

    @Test
    fun `argLine is null without JVM arguments`() {
        assertNull(surefireArgLine(emptyList()))
        assertNull(surefireArgLine(listOf("", " ")))
        assertEquals("-Xmx1g -Da=b", surefireArgLine(listOf("-Xmx1g", "-Da=b")))
    }
}
