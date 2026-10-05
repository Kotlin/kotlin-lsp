// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.util.io.Decompressor
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.gradle.GradleDriver
import com.jetbrains.ls.imports.gradle.GradleModuleMapper
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_DAEMON_NO_IDLE_TIMEOUT
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_JAVA_HOME_PROPERTY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** The Gradle version the fixture's wrapper properties pin; the run side gets the same one. */
private const val FIXTURE_GRADLE_VERSION = "8.14"

/**
 * Builds and runs an imported Gradle project through the live tool, end to end: a real import, then
 * [com.jetbrains.ls.imports.api.BuildTool.run] executes the real Gradle, and the assertions read the
 * process events.
 *
 * The unit tests cover the argv builders and the init script as pure functions. What only an execution shows
 * is that the derived unit names a task the real tool accepts, that the generated init script compiles and
 * forks the program, and that the program receives its arguments and environment. This is the flow the
 * `intellij.build` command and a DAP build-tool launch drive.
 *
 * The fixture is an import test project, copied to a temporary directory because a build writes into the
 * project. Windows is excluded: the generated wrapper script is POSIX, and the unit layers cover the Windows
 * argv shapes.
 */
@DisabledOnOs(OS.WINDOWS)
class GradleRunE2eTest {

    @Test
    fun `an imported gradle project builds and runs through the tool`() {
        val projectDir = copyRunFixture("gradle/IdeaPluginCustomSourceSets")
        writeGradleWrapper(projectDir)
        withSystemProperties(
            LSP_GRADLE_JAVA_HOME_PROPERTY to JdkDownloaderFacade.jdk17.home.toString(),
            LSP_GRADLE_DAEMON_NO_IDLE_TIMEOUT to "true",
        ) {
            withLiveTool(GradleDriver, WorkspaceImportParameters(projectDir, null)) { tool, storage ->
                val module = storage.moduleNamed("IdeaPluginCustomSourceSets.main")
                val unit = GradleModuleMapper.unitOf(module)
                assertEquals("main", unit.sourceSet) { "expected the main source set of $module, got $unit" }

                val build = tool.collectRun(RunRequest(RunTask.Build(unit)))
                build.assertFinishedWithZero()
                assertTrue((projectDir / "build/classes/java/main/com/intellij/Main.class").exists()) {
                    "expected the compiled class after the build, got:\n${build.joinToString("\n")}"
                }

                val run = tool.collectRun(
                    RunRequest(
                        RunTask.Run(unit, "com.intellij.Main"),
                        RunOptions(programArgs = listOf("--port", "8080"), env = mapOf("IDE_RUN_E2E" to "gradle")),
                    )
                )
                run.assertFinishedWithZero()
                assertTrue(RunTaskEvent.StdOutput("IDE run e2e: --port 8080 in gradle") in run) {
                    "expected the program's own line with its arguments and environment, got:\n${run.joinToString("\n")}"
                }
            }
        }
    }

    /**
     * A `gradlew` that execs the downloaded distribution of the version the wrapper properties pin, so
     * [com.jetbrains.ls.imports.gradle.GradleTool.run] launches the same Gradle the sync used. The fixture has the
     * properties but not the script, and `gradle` is not on the `PATH` of a test machine.
     */
    private fun writeGradleWrapper(projectDir: Path) {
        val zip = downloadGradleDistributionZip(FIXTURE_GRADLE_VERSION)
        val distDir = createTempDirectory("gradle-dist-")
        Decompressor.Zip(zip).extract(distDir)
        val gradle = distDir / "gradle-$FIXTURE_GRADLE_VERSION" / "bin" / "gradle"
        @Suppress("IO_FILE_USAGE")
        gradle.toFile().setExecutable(true)
        val wrapper = projectDir / "gradlew"
        wrapper.writeText($$"#!/bin/sh\nexec \"$$gradle\" \"$@\"\n")
        @Suppress("IO_FILE_USAGE")
        wrapper.toFile().setExecutable(true)
    }
}
