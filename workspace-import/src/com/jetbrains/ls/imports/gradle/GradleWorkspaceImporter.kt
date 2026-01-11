// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.intellij.util.io.awaitExit
import com.intellij.util.io.delete
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.*

private val LOG = logger<GradleWorkspaceImporter>()

private const val JB_GRADLE_HOME: String = "JB_GRADLE_HOME"
private const val JB_GRADLE_JAVA_HOME: String = "JB_GRADLE_JAVA_HOME"

object GradleWorkspaceImporter : WorkspaceImporter {

    fun useGradleAndJava(mavenHome: Path, javaHome: Path) {
        System.setProperty(JB_GRADLE_HOME, mavenHome.toString())
        System.setProperty(JB_GRADLE_JAVA_HOME, javaHome.toString())
    }

    private fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        ).any { (projectDirectory / it).exists() }
    }

    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Gradle project from: $projectDirectory")
        val wrapper = projectDirectory / (if (OS.CURRENT == OS.Windows) "gradlew.bat" else "gradlew")
        val gradleHome = System.getProperty(JB_GRADLE_HOME)?.let { Path.of(it) }
        val javaHome = System.getProperty(JB_GRADLE_JAVA_HOME)
            ?: if (System.getenv()["JAVA_HOME"] == null) System.getProperty("java.home") else null
        val execPath = when {
            wrapper.exists() -> wrapper
            gradleHome != null -> gradleHome / "bin" / if (OS.CURRENT == OS.Windows) "gradle.bat" else "gradle"
            else -> Path("gradle")
        }
        LOG.info("Using Gradle: $execPath (JAVA_HOME=${javaHome ?: "unspecified"})")

        val pluginResourcePath = "/META-INF/gradle-plugins/imports-gradle-plugin.properties"
        val pluginJar = PathManager.getResourceRoot(this::class.java, pluginResourcePath)
            ?: error("Corrupted installation: gradle plugin .properties not found")

        val workspaceJsonFile = createTempFile("workspace", ".json")
        val initScriptFile = createTempFile("gradle-init", ".gradle")
        try {
            initScriptFile.writeText(
                """
                    initscript {
                        dependencies {
                            repositories {
                                mavenCentral()
                            }
                            classpath(files("${PathUtil.toSystemIndependentName(pluginJar)}"))
                            classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                        }
                    }
                    apply plugin: com.jetbrains.ls.imports.gradle.InfoPlugin
                """.trimIndent()
            )

            ProcessBuilder(
                execPath.toString(),
                "--no-daemon",
                "--init-script",
                initScriptFile.toAbsolutePath().toString(),
                "-Dworkspace.output.file=${workspaceJsonFile.toAbsolutePath()}",
//                "-Dorg.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
//                "--stacktrace",
                "help", // Dummy task to trigger project evaluation
                "--quiet"
            )
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                }
                .directory(projectDirectory.toFile())
                .inheritIO()
                .runAndGetOK()

            return JsonWorkspaceImporter.importWorkspaceJson(
                workspaceJsonFile, projectDirectory, onUnresolvedDependency, virtualFileUrlManager
            )
        } finally {
            workspaceJsonFile.delete()
            initScriptFile.delete()
        }
    }

    private suspend fun ProcessBuilder.runAndGetOK() {
        val process = try {
            withContext(Dispatchers.IO) {
                start()
            }
        } catch (e: Exception) {
            throw WorkspaceImportException(
                "Failed to start Gradle process",
                "Cannot execute ${command()} in ${directory()}: ${e.message}",
                e
            )
        }
        process.awaitExit()
        if (process.exitValue() != 0) {
            throw WorkspaceImportException(
                "Failed to import Gradle project",
                "Failed to import Gradle project in ${directory()}:\n${command()} returned ${process.exitValue()}"
            )
        }
    }
}
