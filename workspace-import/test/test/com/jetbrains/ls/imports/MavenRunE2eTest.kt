// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.maven.MavenDriver
import com.jetbrains.ls.imports.maven.MavenModuleMapper
import com.jetbrains.ls.imports.maven.MavenTool
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Builds and runs an imported Maven project through the live tool, end to end: a real import, then
 * [com.jetbrains.ls.imports.api.BuildTool.run] executes the real Maven, and the assertions read the
 * process events.
 *
 * The unit tests cover the argv builders as pure functions. What only an execution shows is that the derived
 * unit selects a module the real reactor accepts, that the compile-then-`java` pair runs on the written
 * classpath, and that the program receives its arguments and environment. This is the flow the
 * `intellij.build` command and a DAP build-tool launch drive.
 *
 * The fixture is an import test project, copied to a temporary directory because a build writes into the
 * project. Windows is excluded like the other process tests of this module.
 */
@DisabledOnOs(OS.WINDOWS)
class MavenRunE2eTest {

    @Test
    fun `an imported maven project builds and runs through the tool`() {
        val projectDir = copyRunFixture("maven/MavenAnnotationProcessing")
        MavenTool.useMavenAndJava(downloadMavenBinaries(), JdkDownloaderFacade.jdk17.home)
        val parameters = WorkspaceImportParameters(projectDir, null).let {
            it.copy(options = it.options.copy(environment = it.options.environment + ("MAVEN_ARGS" to "-s $mavenSettingsFile")))
        }
        withLiveTool(MavenDriver, parameters) { tool, storage ->
            val module = storage.moduleNamed("annotation-processing")
            val unit = MavenModuleMapper.unitOf(module)

            val build = tool.collectRun(RunRequest(RunTask.Build(unit)))
            build.assertFinishedWithZero()
            assertTrue((projectDir / "target/classes/com/example/Main.class").exists()) {
                "expected the compiled class after the build, got:\n${build.joinToString("\n")}"
            }

            val run = tool.collectRun(
                RunRequest(
                    RunTask.Run(unit, "com.example.Main"),
                    RunOptions(programArgs = listOf("--port", "8080"), env = mapOf("IDE_RUN_E2E" to "maven")),
                )
            )
            run.assertFinishedWithZero()
            assertTrue(RunTaskEvent.StdOutput("IDE run e2e: --port 8080 in maven") in run) {
                "expected the program's own line with its arguments and environment, got:\n${run.joinToString("\n")}"
            }
        }
    }

    private companion object {
        /** Maven resolves through the cache redirector, like the import tests. */
        val mavenSettingsFile: Path by lazy {
            createTempFile("maven-cache-redirector-settings", ".xml").also { file ->
                file.writeText(
                    """
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                      <mirrors>
                        <mirror>
                          <id>cache-redirector</id>
                          <name>JetBrains cache redirector</name>
                          <url>https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2</url>
                          <mirrorOf>central</mirrorOf>
                        </mirror>
                      </mirrors>
                    </settings>
                    """.trimIndent()
                )
            }
        }
    }
}
