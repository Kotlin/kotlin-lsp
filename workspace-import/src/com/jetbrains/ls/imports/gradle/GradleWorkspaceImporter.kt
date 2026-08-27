// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.addInitScripts
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.configureEnvironment
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.configureLogging
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.configureSystemProperties
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.findTheMostCompatibleJdk
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.prepareForExecution
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.withCustomGradleHome
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.withDaemonInitScripts
import com.jetbrains.ls.imports.gradle.action.GradleSyncSettings
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.action.ProjectMetadataBuilder
import com.jetbrains.ls.imports.gradle.model.builder.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.BuildActionFailureException
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = logger<GradleWorkspaceImporter>()

object GradleWorkspaceImporter : WorkspaceImporter {

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean {
        return listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        ).any { (projectFileOrDirectory / it).exists() }
    }

    override suspend fun importWorkspace(
        project: Project,
        parameters: WorkspaceImportParameters,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter,
    ): EntityStorage? {
        val projectDirectory = parameters.projectDirectory
        val defaultSdkPath = parameters.defaultSdkPath
        if (!canImportWorkspace(projectDirectory)) {
            return null
        }
        LOG.info("Importing Gradle project from: $projectDirectory")
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .withCustomGradleHome()
            .connect()

        // A `java-home` configured for this project wins over auto-detection.
        val jdkToUse = parameters.options.javaHome?.toString()
            ?: findTheMostCompatibleJdk(project, projectDirectory)

        val gradleProjectData = try {
            connection.use { projectConnection ->
                withDaemonInitScripts { daemonInitScripts ->
                    try {
                        createExecuter(
                            parameters,
                            projectConnection,
                            progress,
                            daemonInitScripts,
                            jdkToUse,
                            listOf(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME)
                        ).run()
                    } catch (e: BuildActionFailureException) {
                        LOG.warn(
                            "Gradle sync failed while running '$PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME'. " +
                                    "Falling back to importing $projectDirectory without sync tasks; generated sources may be missing.",
                            e
                        )
                        progress.onErrorOutput(
                            "Gradle sync tasks failed, retrying the import without them. Generated sources may be missing."
                        )
                        // retry without kotlin task in case of a broken task graph
                        createExecuter(parameters, projectConnection, progress, daemonInitScripts, jdkToUse, null).run()
                    }
                }
            }
        } catch (e: GradleConnectionException) {
            @Suppress("HardCodedStringLiteral")
            throw WorkspaceImportException("Gradle sync failed", "Unable to import a Gradle project: ${e.message}", e)
        }
        val entitySource = WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager))
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    IdeaProjectMapper().toWorkspaceData(gradleProjectData, projectDirectory),
                    projectDirectory,
                    progress
                ),
                projectDirectory,
                entitySource,
                virtualFileUrlManager,
                ignoreDuplicateLibsAndSdks = true,
                "GRADLE"
            )
            fixMissingProjectSdk(parameters.options.javaHome ?: defaultSdkPath, virtualFileUrlManager)
        }
    }

    /**
     * @param syncTasks The paths of the tasks to be executed.
     * Relative paths are evaluated relative to the project for which this launcher was created.
     * An empty list will run the project's default tasks.
     * A null means no tasks will be executed
     */
    private fun createExecuter(
        parameters: WorkspaceImportParameters,
        connection: ProjectConnection,
        progress: WorkspaceImportProgressReporter,
        initScripts: Iterable<Path>,
        javaHome: String?,
        syncTasks: List<String>? = null,
    ): BuildActionExecuter<ProjectMetadata> {
        val syncSettings = GradleSyncSettings(downloadLibrarySources = true)
        val executer = connection.action(ProjectMetadataBuilder(syncSettings))
            .configureLogging(progress)
            .prepareForExecution()
            .configureEnvironment(parameters.options.environment)
            .configureSystemProperties(parameters.options.systemProperties)
            .addInitScripts(initScripts)
            .forTasks(syncTasks)

        if (javaHome != null) {
            executer.setJavaHome(File(javaHome))
        }
        return executer
    }
}
