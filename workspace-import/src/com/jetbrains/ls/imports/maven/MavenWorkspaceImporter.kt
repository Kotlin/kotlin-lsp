// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.io.delete
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.imports.utils.runAndGetOK
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

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
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Maven project from: $projectDirectory")
        val wrapper = projectDirectory / (if (OS.CURRENT == OS.Windows) "mvnw.cmd" else "mvnw")
        val mavenHome = System.getProperty(JB_MAVEN_HOME)?.let { Path.of(it) }
        val javaHome = System.getProperty(JB_MAVEN_JAVA_HOME)
            ?: if (System.getenv()["JAVA_HOME"] == null) System.getProperty("java.home") else null
        val execPath = when {
            wrapper.exists() -> wrapper
            mavenHome != null -> mavenHome / "bin" / if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn"
            else -> Path.of(if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn")
        }
        LOG.info("Using Maven: $execPath (JAVA_HOME=${javaHome ?: "unspecified"})")

        val pomResourcePath = "/META-INF/maven/com.jetbrains.ls/imports.maven.plugin/pom.xml"
        val pluginJar = PathManager.getResourceRoot(this::class.java, pomResourcePath)
            ?: error("Corrupted installation: maven plugin jar not found")

        val pluginPom = javaClass.getResource(pomResourcePath)?.readText()?.takeIf { it.isNotEmpty() }
            ?: error("Corrupted installation: maven plugin pom.xml not found")

        val mavenPluginPomFile = createTempFile("mavenPlugin-pom", ".xml")
        try {
            mavenPluginPomFile.writeText(pluginPom)
            ProcessBuilder(
                execPath.toString(),
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
                .runAndGetOK("Maven", processOutputLogger = LOG)
        } finally {
            mavenPluginPomFile.delete()
        }

        val workspaceJsonFile = createTempFile("workspace", ".json")
        try {
            ProcessBuilder(
                execPath.toString(),
                "com.jetbrains.ls:imports-maven-plugin:info",
                "-f",
                "pom.xml",
                "-DoutputFile=${workspaceJsonFile.toAbsolutePath()}"
            )
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                    if(System.getProperty("maven.importer.debug").toBoolean()) {
                        environment()["MAVEN_OPTS"] = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
                    }

                }
                .directory(projectDirectory.toFile())
                .runAndGetOK("Maven", processOutputLogger = LOG)

            return JsonWorkspaceImporter.importWorkspaceJson(
                workspaceJsonFile, projectDirectory, defaultSdkPath, virtualFileUrlManager, onUnresolvedDependency
            )
        } finally {
            workspaceJsonFile.delete()
        }
    }
}