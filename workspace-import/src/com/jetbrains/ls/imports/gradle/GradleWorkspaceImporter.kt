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
import com.jetbrains.ls.imports.gradle.action.PrepareKotlinIdeaImportAction
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.action.ProjectMetadataBuilder
import com.jetbrains.ls.imports.gradle.util.GradleSyncResultHandler
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.IntermediateResultHandler
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
                    val metadata = executeGradleSync(parameters, projectConnection, channel, daemonInitScripts, jdkToUse)
                    channel.trySend(
                        ImportEvent.UpdateWorkspaceModel(
                            toStorage(
                                metadata,
                                parameters,
                                virtualFileUrlManager,
                                channel
                            )
                        )
                    )
                    channel.trySend(ImportEvent.StdOutput("Gradle execution complete"))
                }
            }
        } catch (e: Exception) {
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

    private fun executeGradleSync(
        parameters: WorkspaceImportParameters,
        connection: ProjectConnection,
        events: SendChannel<ImportEvent>,
        initScripts: Iterable<Path>,
        javaHome: String?
    ): ProjectMetadata {
        val syncSettings = GradleSyncSettings(downloadLibrarySources = parameters.options.downloadAdditionalArtifacts)
        val syncResultHandler = GradleSyncResultHandler<ProjectMetadata>()
        val executer = connection.action()
            .projectsLoaded(
                PrepareKotlinIdeaImportAction(),
                IntermediateResultHandler { }
            )
            .buildFinished(
                ProjectMetadataBuilder(syncSettings),
                syncResultHandler.asIntermediateHandler()
            )
            .build()
            .configureLogging(events)
            .prepareForExecution()
            .configureEnvironment(parameters.options.environment)
            .configureSystemProperties(parameters.options.systemProperties)
            .addInitScripts(initScripts)
            .forTasks(emptyList())

        if (parameters.options.offline) {
            executer.addArguments("--offline")
        }
        if (javaHome != null) {
            executer.setJavaHome(File(javaHome))
        }
        executer.run(syncResultHandler.asResultHandler())
        return syncResultHandler.getSyncResult()
    }
}
