// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
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

private val LOG = logger<MavenWorkspaceImporter>()

private const val JB_MAVEN_HOME: String = "JB_MAVEN_HOME"
private const val JB_MAVEN_JAVA_HOME: String = "JB_MAVEN_JAVA_HOME"

object MavenWorkspaceImporter : WorkspaceImporter {

    fun useMavenAndJava(mavenHome: Path, javaHome: Path) {
        System.setProperty(JB_MAVEN_HOME, mavenHome.toString())
        System.setProperty(JB_MAVEN_JAVA_HOME, javaHome.toString())
    }

    private fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return (projectDirectory / "pom.xml").exists()
    }

    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): MutableEntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Maven project from: $projectDirectory")
        val wrapper = projectDirectory / (if (OS.CURRENT == OS.Windows) "mvnw.cmd" else "mvnw")
        val mavenHome = System.getProperty(JB_MAVEN_HOME)?.let { Path.of(it) }
        val javaHome = System.getProperty(JB_MAVEN_JAVA_HOME)
            ?: if (System.getenv()["JAVA_HOME"] == null) System.getProperty("java.home") else null
        val command = when {
            wrapper.exists() -> (Path.of(".") / wrapper.name).toString()
            mavenHome != null -> (mavenHome / "bin" / if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn").toString()
            else -> "mvn"
        }
        LOG.info("Using Maven: $command (JAVA_HOME=${javaHome ?: "unspecified"})")

        val pomResourcePath = "/META-INF/maven/com.jetbrains.ls/imports.maven.plugin/pom.xml"

        val pluginJar = PathManager.getResourceRoot(this::class.java, pomResourcePath)
            ?: error("Corrupted installation: maven plugin jar not found")

        val pluginPom = javaClass.getResource(pomResourcePath)?.readText()?.takeIf { it.isNotEmpty() }
            ?: error("Corrupted installation: maven plugin pom.xml not found")

        val mavenPluginPomFile = createTempFile("mavenPlugin-pom", ".xml")
        try {
            mavenPluginPomFile.writeText(pluginPom)
            ProcessBuilder(
                command,
                "install:install-file",
                "-Dfile=$pluginJar",
                "-DpomFile=$mavenPluginPomFile",
                "-DgroupId=com.jetbrains.ls",
                "-DartifactId=imports-maven-plugin",
                "-Dversion=0.99",
                "-Dpackaging=maven-plugin"
            )
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                }
                .directory(projectDirectory.toFile())
                .inheritIO()
                .runAndGetOK()
        } finally {
            mavenPluginPomFile.delete()
        }

        val workspaceJsonFile = createTempFile("workspace", ".json")
        try {
            ProcessBuilder(
                command,
                "com.jetbrains.ls:imports-maven-plugin:info",
                "-f",
                "pom.xml",
                "-DoutputFile=${workspaceJsonFile.toAbsolutePath()}"
            )
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
//                    environment()["MAVEN_OPTS"] = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
                }
                .directory(projectDirectory.toFile())
                .inheritIO()
                .runAndGetOK()

            return JsonWorkspaceImporter.importWorkspaceJson(
                workspaceJsonFile, projectDirectory, onUnresolvedDependency, virtualFileUrlManager
            )
        } finally {
            workspaceJsonFile.delete()
        }
    }

    private suspend fun ProcessBuilder.runAndGetOK() {
        val process = try {
            withContext(Dispatchers.IO) {
                start()
            }
        } catch (e: Exception) {
            throw WorkspaceImportException(
                "Failed to start Maven process",
                "Cannot execute ${command()} in ${directory()}: ${e.message}",
                e
            )
        }
        process.awaitExit()
        if (process.exitValue() != 0) {
            throw WorkspaceImportException(
                "Failed to import Maven project",
                "Failed to import Maven project in ${directory()}:\n${command()} returned ${process.exitValue()}"
            )
        }
    }
}