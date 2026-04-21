// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.io.delete
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import com.jetbrains.ls.imports.utils.runWithErrorReporting
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

private val LOG = logger<MavenWorkspaceImporter>()

private const val JB_MAVEN_HOME: String = "JB_MAVEN_HOME"
private const val JB_MAVEN_JAVA_HOME: String = "JB_MAVEN_JAVA_HOME"

object MavenWorkspaceImporter : WorkspaceImporter {
    const val LSP_MAVEN_PROJECT_OFFLINE_PROPERTY: String = "com.jetbrains.ls.imports.maven.offline"
    const val LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY: String = "com.jetbrains.ls.imports.maven.mavenUserHome"
    const val LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY: String = "com.jetbrains.ls.imports.maven.opts"


    fun useMavenAndJava(mavenHome: Path, javaHome: Path) {
        System.setProperty(JB_MAVEN_HOME, mavenHome.toString())
        System.setProperty(JB_MAVEN_JAVA_HOME, javaHome.toString())
    }

    override fun canImportWorkspace(projectDirectory: Path): Boolean {
        return (projectDirectory / "pom.xml").exists()
    }

    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter,
    ): EntityStorage? {
        if (!canImportWorkspace(projectDirectory)) return null

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


        val offlineOpts = if (System.getProperty(LSP_MAVEN_PROJECT_OFFLINE_PROPERTY).toBoolean()) listOf("-o") else emptyList()
        progress.progressStatus("Installing Maven plugin...")
        installMavenPlugin(execPath, javaHome, projectDirectory, progress, offlineOpts)


        progress.progressStatus("Collecting Maven model...")
        val modelWithDeps = runMavenPluginGoal(execPath, javaHome, projectDirectory, "model-with-deps", progress, offlineOpts)
        val modelWithGeneratedSources =
            modelWithGeneratedSources(modelWithDeps is SuccessResult, execPath, javaHome, projectDirectory, progress, offlineOpts)
        progress.progressStatus("Maven model collected, commiting...")
        val mergedModels = mergeResults(modelWithDeps, modelWithGeneratedSources)

        when (mergedModels) {
            is ErrorResult -> throw mergedModels.e
            is SuccessResult -> return MutableEntityStorage.create().apply {
                importWorkspaceData(
                    JsonWorkspaceImporter.postProcessWorkspaceData(
                        mergedModels.workspaceData,
                        projectDirectory,
                        progress
                    ),
                    projectDirectory,
                    WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
                    virtualFileUrlManager
                )
                fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
            }
        }
    }

    private suspend fun modelWithGeneratedSources(
        depsResolved: Boolean,
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String>
    ): MavenRunResult {
        progress.progressStatus("Generating sources...")
        val modelWithGeneratedSources = runMavenPluginGoal(execPath, javaHome, projectDirectory, "gen-sources", progress, additionalParams)
        if (modelWithGeneratedSources is ErrorResult && couldBeFixedWithInstall(depsResolved, modelWithGeneratedSources)) {
            progress.progressStatus("Generating sources failed, trying to fix with mvn install...")
            runGoal(execPath, javaHome, projectDirectory, "install", progress, additionalParams)
            progress.progressStatus("Generating sources, second try...")
            return runMavenPluginGoal(execPath, javaHome, projectDirectory, "gen-sources", progress, additionalParams)
        } else return modelWithGeneratedSources
    }

    private fun couldBeFixedWithInstall(depsResolved: Boolean, modelWithGeneratedSources: ErrorResult): Boolean {
        return depsResolved //todo
    }

    private suspend fun runMavenPluginGoal(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        pluginGoal: String,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String> = emptyList()
    ): MavenRunResult {
        return runGoal(
            execPath, javaHome, projectDirectory,
            "com.jetbrains.ls:imports-maven-plugin:$pluginGoal",
            progress, additionalParams
        )
    }

    private suspend fun runGoal(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        goal: String,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String> = emptyList()
    ): MavenRunResult {

        val mavenUserHomeProperty = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY)
        val mavenOpts = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY)
        val workspaceJsonFile = createTempFile("workspace", ".json")
        try {
            val command = listOf(
                execPath.toString(),
                goal,
                "-f",
                "pom.xml",
                "-DoutputFile=${workspaceJsonFile.toAbsolutePath()}",
                "-Denforcer.skip=true",
                "-DskipTests=true",
                "-Dmaven.enforcer.skip=true",
                "-Denforcer.skip=true",
                "-Dair.check.skip-enforcer=true"

            )
            ProcessBuilder(command + additionalParams)
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                    if (System.getProperty("maven.importer.debug").toBoolean()) {
                        val agentLibOpt = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
                        val currentMavenOpts = environment()["MAVEN_OPTS"]
                        environment()["MAVEN_OPTS"] = if (currentMavenOpts.isNullOrEmpty()) {
                            agentLibOpt
                        } else {
                            "$currentMavenOpts $agentLibOpt"
                        }
                    }
                    mavenUserHomeProperty?.let {
                        environment()["MAVEN_USER_HOME"] = it
                    }
                    mavenOpts?.let {
                        environment()["MAVEN_OPTS"] = it
                    }

                }
                .directory(projectDirectory.toFile())
                .runWithErrorReporting("Maven", progress)

            return SuccessResult(workspaceJsonFile.inputStream().use<InputStream, WorkspaceData> { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream<WorkspaceData>(stream)

            })
        } catch (e: SerializationException) {
            return ErrorResult(
                WorkspaceImportException(
                    "Error parsing workspace.json",
                    "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
                    e
                )
            )
        } catch (e: WorkspaceImportException) {
            return ErrorResult(e)
        } finally {
            workspaceJsonFile.delete()
        }

    }

    private suspend fun installMavenPlugin(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String> = emptyList()
    ) {
        val pomResourcePath = "/META-INF/maven/com.jetbrains.ls/imports.maven.plugin/pom.xml"
        val pluginJar = PathManager.getResourceRoot(this::class.java, pomResourcePath)
            ?: error("Corrupted installation: maven plugin jar not found")

        val pluginPom = javaClass.getResource(pomResourcePath)?.readText()?.takeIf { it.isNotEmpty() }
            ?: error("Corrupted installation: maven plugin pom.xml not found")

        val mavenPluginPomFile = createTempFile("mavenPlugin-pom", ".xml")
        val mavenUserHomeProperty = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY)
        val mavenOpts = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY)
        try {
            mavenPluginPomFile.writeText(pluginPom)
            val command = listOf(
                execPath.toString(),
                "install:install-file",
                "-Dfile=$pluginJar",
                "-DpomFile=$mavenPluginPomFile",
                "-DgroupId=com.jetbrains.ls",
                "-DartifactId=imports-maven-plugin",
                "-Dversion=0.99",
                "-Dpackaging=maven-plugin"
            )
            ProcessBuilder(command + additionalParams)
                .apply {
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                    mavenUserHomeProperty?.let {
                        environment()["MAVEN_USER_HOME"] = it
                    }
                    mavenOpts?.let {
                        environment()["MAVEN_OPTS"] = it
                    }
                }
                .directory(projectDirectory.toFile())
                .runWithErrorReporting("Maven", progress)
        } finally {
            mavenPluginPomFile.delete()
        }
    }
}

