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
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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

    /**
     * Publishes the model as Gradle declares it first, then republishes it after the sync tasks have generated their
     * sources, so the analyzer does not wait for code generation before it can resolve the project's dependencies.
     *
     * The price is one extra model build in the success case; the failure case costs what it did before, because the
     * fallback this replaces was the very same build without the sync tasks.
     */
    override fun importWorkspace(
        project: Project,
        parameters: WorkspaceImportParameters,
        virtualFileUrlManager: VirtualFileUrlManager,
    ): Flow<ImportEvent> = channelFlow {
        val projectDirectory = parameters.projectDirectory
        if (!canImportWorkspace(projectDirectory)) return@channelFlow
        LOG.info("Importing Gradle project from: $projectDirectory")
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .withCustomGradleHome()
            .connect()

        // A `java-home` configured for this project wins over auto-detection.
        val jdkToUse = parameters.options.javaHome?.toString()
            ?: findTheMostCompatibleJdk(project, projectDirectory)

        // The models are handed over with `trySend` (the channel is unbounded): the Tooling API calls below are
        // blocking and run inside non-suspending lambdas.
        try {
            connection.use { projectConnection ->
                withDaemonInitScripts { daemonInitScripts ->
                    // Phase 1: the model as declared, with the sync tasks not run yet, so nothing waits on code generation.
                    val withoutSyncTasks = createExecuter(parameters, projectConnection, channel, daemonInitScripts, jdkToUse, null).run()
                    channel.trySend(ImportEvent.UpdateWorkspaceModel(toStorage(withoutSyncTasks, parameters, virtualFileUrlManager, channel)))

                    // Phase 2: the same model once the sync tasks have generated their sources.
                    val withSyncTasks = try {
                        createExecuter(
                            parameters,
                            projectConnection,
                            channel,
                            daemonInitScripts,
                            jdkToUse,
                            listOf(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME)
                        ).run()
                    } catch (e: BuildActionFailureException) {
                        LOG.warn(
                            "Gradle sync failed while running '$PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME' in $projectDirectory. " +
                                    "Keeping the model imported without sync tasks; generated sources may be missing.",
                            e
                        )
                        // Reported as output rather than as `Failed`, which would show the client an error for an import
                        // that succeeded, only without generated sources.
                        channel.trySend(ImportEvent.ErrorOutput("Gradle sync tasks failed. Generated sources may be missing."))
                        return@withDaemonInitScripts
                    }
                    channel.trySend(ImportEvent.UpdateWorkspaceModel(toStorage(withSyncTasks, parameters, virtualFileUrlManager, channel)))
                }
            }
        } catch (e: GradleConnectionException) {
            @Suppress("HardCodedStringLiteral")
            throw WorkspaceImportException("Gradle sync failed", "Unable to import a Gradle project: ${e.message}", e)
        }
    }.buffer(Channel.UNLIMITED)

    private fun toStorage(
        gradleProjectData: ProjectMetadata,
        parameters: WorkspaceImportParameters,
        virtualFileUrlManager: VirtualFileUrlManager,
        events: SendChannel<ImportEvent>,
    ): EntityStorage {
        val projectDirectory = parameters.projectDirectory
        val entitySource = WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager))
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    IdeaProjectMapper().toWorkspaceData(gradleProjectData, projectDirectory),
                    projectDirectory,
                    onUnresolvedDependency = { events.trySend(ImportEvent.UnresolvedDependency(it)) },
                ),
                projectDirectory,
                entitySource,
                virtualFileUrlManager,
                ignoreDuplicateLibsAndSdks = true,
                "GRADLE"
            )
            fixMissingProjectSdk(parameters.options.javaHome ?: parameters.defaultSdkPath, virtualFileUrlManager)
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
        events: SendChannel<ImportEvent>,
        initScripts: Iterable<Path>,
        javaHome: String?,
        syncTasks: List<String>? = null,
    ): BuildActionExecuter<ProjectMetadata> {
        val syncSettings = GradleSyncSettings(downloadLibrarySources = true)
        val executer = connection.action(ProjectMetadataBuilder(syncSettings))
            .configureLogging(events)
            .prepareForExecution()
            .configureEnvironment(parameters.options.environment)
            .configureSystemProperties(parameters.options.systemProperties)
            .addInitScripts(initScripts)
            .forTasks(syncTasks)

        if (parameters.options.offline) {
            executer.addArguments("--offline")
        }
        if (javaHome != null) {
            executer.setJavaHome(File(javaHome))
        }
        return executer
    }
}
