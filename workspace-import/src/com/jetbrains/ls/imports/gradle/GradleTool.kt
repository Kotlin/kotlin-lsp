// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.filesystem.forEach
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.FullImportRequest
import com.jetbrains.ls.imports.api.ImportRequest
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
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
import com.jetbrains.ls.imports.utils.stampBuildToolJavaHome
import com.jetbrains.ls.snapshot.api.impl.core.rocks.FileSystemChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ProjectConnection
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.div

private val LOG = logger<GradleTool>()

/** One folder's live Gradle build tool; [GradleDriver] starts it. */
class GradleTool(
    toolContext: BuildToolDriverContext,
    private val parameters: WorkspaceImportParameters,
) : BuildTool {

    override fun sync(context: BuildToolContext, request: ImportRequest): Flow<ImportEvent> = flow {
        try {
            emitAll(importWorkspace(context.project, context.virtualFileUrlManager))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ImportEvent.Failed(e))
        }
    }

    /**
     * The fixed-location configuration of this target: the settings script, the root `gradle.properties`,
     * and the wrapper. Read by every Gradle build regardless of the module layout.
     */
    private val settingsFiles: Set<Path> = with(parameters.projectDirectory) {
        setOf(
            this / "settings.gradle",
            this / "settings.gradle.kts",
            this / "gradle.properties",
            this / "gradlew",
            this / "gradlew.bat",
            this / "gradle" / "wrapper" / "gradle-wrapper.properties",
        )
    }

    /** A change to a build script the last import read, or to a settings file of this target, asks for a re-import. */
    override val reimportRequests: Flow<ImportRequest> =
        toolContext.fileChanges.mapNotNull { change ->
            when (change) {
                is FileSystemChange.Invalidate -> {
                    // Read per event: the committed model lists the module directories the last import produced.
                    val watched = settingsFiles + importedBuildFiles(toolContext.entityStorage())
                    val changed = buildList {
                        change.files.forEach { file -> pathOf(file)?.takeIf { it in watched }?.let(::add) }
                    }
                    when {
                        changed.isEmpty() -> null
                        else -> {
                            LOG.info("Gradle settings files changed: ${changed.joinToString()}")
                            FullImportRequest
                        }
                    }
                }
                FileSystemChange.Rescan -> {
                    LOG.info("The file watcher lost changes, so a Gradle settings file may have changed too")
                    FullImportRequest
                }
            }
        }

    /**
     * The build scripts the last import read, derived from the committed model: every Gradle module of this
     * target carries its project directory, and each directory may hold `build.gradle`, `build.gradle.kts`,
     * or its own `gradle.properties`. Both script spellings are watched because only one exists on disk, so
     * the absent one never fires. Scoped to this target's directory: the workspace watcher reports every
     * target's files. Read from the model rather than remembered, so it stays correct across a restart that
     * restores the model from cache without running an import.
     */
    private fun importedBuildFiles(storage: EntityStorage): Set<Path> {
        val projectDirectory = parameters.projectDirectory
        val files = mutableSetOf<Path>()
        storage.entities(ModuleEntity::class.java)
            .mapNotNull { it.exModuleOptions }
            .filter { it.externalSystem == GRADLE_EXTERNAL_SYSTEM_ID }
            .mapNotNull { it.linkedProjectPath?.let(::pathAt) }
            .filter { it.startsWith(projectDirectory) }
            .forEach { dir ->
                files.add(dir / "build.gradle")
                files.add(dir / "build.gradle.kts")
                files.add(dir / "gradle.properties")
            }
        return files
    }

    private fun pathAt(value: String): Path? = try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        null
    }

    private fun pathOf(file: FileUrl): Path? = try {
        Path.of(file.path)
    } catch (_: InvalidPathException) {
        null
    }

    /**
     * Publishes the model as Gradle declares it first, then republishes it after the sync tasks have generated their
     * sources, so the analyzer does not wait for code generation before it can resolve the project's dependencies.
     */
    private fun importWorkspace(
        project: Project,
        virtualFileUrlManager: VirtualFileUrlManager,
    ): Flow<ImportEvent> = channelFlow {
        val projectDirectory = parameters.projectDirectory
        if (!GradleDriver.canImportWorkspace(projectDirectory)) return@channelFlow
        LOG.info("Importing Gradle project from: $projectDirectory")
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .withCustomGradleHome()
            .connect()

        // A `java-home` configured for this project wins over `JAVA_HOME` and auto-detection.
        val jdkToUse = parameters.options.javaHome?.toString()
            ?: findTheMostCompatibleJdk(project, projectDirectory, parameters.options.environment)

        // The models are handed over with `trySend` (the channel is unbounded): the Tooling API calls below are
        // blocking and run inside non-suspending lambdas.
        try {
            connection.use { projectConnection ->
                withDaemonInitScripts { daemonInitScripts ->
                    val metadata = executeGradleSync(projectConnection, channel, daemonInitScripts, jdkToUse)
                    channel.trySend(
                        ImportEvent.UpdateWorkspaceModel(
                            toStorage(
                                metadata,
                                virtualFileUrlManager,
                                channel,
                                jdkToUse
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
        virtualFileUrlManager: VirtualFileUrlManager,
        events: SendChannel<ImportEvent>,
        javaHome: String?,
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
                GRADLE_EXTERNAL_SYSTEM_ID
            )
            fixMissingProjectSdk(parameters.options.javaHome ?: parameters.defaultSdkPath, virtualFileUrlManager)
            stampGradleJavaHome(javaHome)
        }
    }

    private fun executeGradleSync(
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

private const val GRADLE_EXTERNAL_SYSTEM_ID = "GRADLE"

/**
 * Records [javaHome] as [com.jetbrains.ls.imports.api.importJavaHome] on every module the Gradle importer produced.
 * A `null` or blank [javaHome] records nothing, see [stampBuildToolJavaHome].
 */
@ApiStatus.Internal
fun MutableEntityStorage.stampGradleJavaHome(javaHome: String?) {
    stampBuildToolJavaHome(GRADLE_EXTERNAL_SYSTEM_ID, javaHome)
}
