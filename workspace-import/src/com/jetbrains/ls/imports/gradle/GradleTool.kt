// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.openapi.application.PathManager
import com.intellij.util.io.DigestUtil
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.filesystem.forEach
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.FullImportRequest
import com.jetbrains.ls.imports.api.ImportRequest
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.api.run.UnsupportedRunException
import com.jetbrains.ls.api.run.failedRunHandle
import com.jetbrains.ls.imports.api.putEnvironment
import com.jetbrains.ls.api.run.freePort
import com.jetbrains.ls.api.run.jdwpAgent
import com.jetbrains.ls.api.run.toRunHandle
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
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.postProcessWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import com.jetbrains.ls.imports.utils.stampBuildToolJavaHome
import com.jetbrains.ls.snapshot.api.impl.core.rocks.FileSystemChange
import fleet.util.async.Resource
import fleet.util.async.map
import fleet.util.async.resourceOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ProjectConnection
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

private val LOG = logger<GradleTool>()

/** One folder's live Gradle build tool; [GradleDriver] starts it. */
class GradleTool(
    private val toolContext: BuildToolDriverContext,
    private val parameters: WorkspaceImportParameters,
) : BuildTool {

    // The `catch` operator, not a try around `emitAll`: a try also catches what the collector threw through
    // `emit`, and the `Failed` it then emits violates flow exception transparency. `catch` sees upstream only.
    override fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent> =
        flow { emitAll(importWorkspace(context.project, context.virtualFileUrlManager, request.targetWatermark)) }
            // A sync may list build scripts the last model did not; their directories must be watched from now on.
            .onEach { event -> if (event is ImportEvent.UpdateWorkspaceModel) registerWatchedDirectories(event.storage) }
            .catch { e ->
                rethrowControlFlowException(e)
                emit(ImportEvent.Failed(e))
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

    init {
        registerWatchedDirectories(toolContext.entityStorage())
    }

    /**
     * Registers the directories of this target's inputs with the workspace file watcher: the settings files'
     * fixed locations, the `gradle` directory (the version catalogs), and the directory of every build script
     * [storage] lists. Indexing watches the source roots only, and the scripts sit beside them. Called at
     * start with the committed model and after every sync result.
     */
    private fun registerWatchedDirectories(storage: EntityStorage) {
        val directories = (settingsFiles + importedBuildFiles(storage)).mapNotNullTo(linkedSetOf()) { it.parent }
        directories.add(parameters.projectDirectory / "gradle")
        directories.forEach(toolContext.watcher::watch)
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

    /** The JDK the last sync ran Gradle with; a run before the first sync uses the configured `java-home` or none. */
    @Volatile
    private var syncJavaHome: String? = parameters.options.javaHome?.toString()

    /** The Gradle the last sync ran. Without a wrapper, the Tooling API downloads it, so it is not on the `PATH`. */
    @Volatile
    private var syncGradleHome: String? = null

    /**
     * One `gradle` process per request, see [gradleExecutable]. `JAVA_HOME` is the
     * JDK of the import, so the wrapper starts a JVM the Gradle version supports. The program is forked by the
     * daemon, so its arguments, environment and working directory go through the init script; see
     * [gradleInitScript]. With `debug` the JDWP agent travels as a system property, and the handle reports
     * [RunTaskEvent.DebuggerReady] first: the JVM waits for the debugger.
     *
     * A build of the whole root ([RunTask.Build] with an empty unit) names the compile task of every source set
     * the model records, see [gradleWorkspaceTasks].
     */
    override fun run(request: RunRequest): Resource<RunHandle> {
        request.extensions.firstOrNull()?.let {
            return resourceOf(failedRunHandle(UnsupportedRunException("Gradle does not support ${it::class.simpleName}")))
        }
        val task = request.task
        val options = request.options
        val debugPort = if (request.debug && task !is RunTask.Build) freePort() else null
        val initScript = if (task is RunTask.Build) null else gradleInitScript(task, options)
        val launchDir = initScript?.let { writeInitScript(it) }
        val args = if (task is RunTask.Build && task.unit.projectPath.isNullOrBlank()) {
            gradleWorkspaceArgs(gradleWorkspaceTasks(toolContext.entityStorage(), parameters.projectDirectory), options)
        } else {
            gradleArgs(
                task, options,
                initScript = launchDir?.let { (it / GRADLE_INIT_SCRIPT_NAME).toString() },
                debugAgent = listOfNotNull(debugPort?.let(::jdwpAgent)),
            )
        }
        val process = ProcessBuilder(listOf(gradleExecutable()) + args).apply {
            syncJavaHome?.let { environment()["JAVA_HOME"] = it }
            environment().putEnvironment(parameters.options.environment)
        }.directory(parameters.projectDirectory.toFile())
        return process.toRunHandle().map { handle ->
            object : RunHandle by handle {
                override val events: Flow<RunTaskEvent> = handle.events
                    .onStart { debugPort?.let { emit(RunTaskEvent.DebuggerReady("127.0.0.1", it)) } }
            }
        }
    }

    /**
     * Writes [script] into a directory named by its content, under the IDE temp directory, and returns it.
     *
     * Gradle fingerprints the path and the content of an init script as a configuration input. A fresh directory
     * per launch would discard the configuration cache on every Run and Debug; one directory per content keeps it
     * valid, and Run and Debug of one configuration share it, because the debug agent stays out of the script.
     * The directory is never deleted: nothing knows when the daemon has read it, and the content hash bounds
     * how many there are. A file whose bytes already match is left alone, so a concurrent launch never sees a
     * truncated script.
     */
    private fun writeInitScript(script: String): Path {
        val bytes = script.encodeToByteArray()
        val dir = Path.of(PathManager.getTempPath()) / ("lsp-gradle-run-" + DigestUtil.sha256Hex(bytes).take(16))
        Files.createDirectories(dir)
        val file = dir / GRADLE_INIT_SCRIPT_NAME
        if (runCatching { file.readBytes() }.getOrNull().contentEquals(bytes)) return dir
        val temp = Files.createTempFile(dir, "$GRADLE_INIT_SCRIPT_NAME.", ".tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return dir
    }

    /**
     * `gradlew` (`gradlew.bat`) in the project directory when the project ships one, else the `gradle` (`gradle.bat`)
     * of the last sync, else `gradle` (`gradle.bat`) on the `PATH`.
     */
    @OptIn(LowLevelLocalMachineAccess::class)
    private fun gradleExecutable(): String {
        val windows = OS.CURRENT == OS.Windows
        val wrapper = parameters.projectDirectory / if (windows) "gradlew.bat" else "gradlew"
        if (wrapper.isRegularFile()) return wrapper.toString()
        val name = if (windows) "gradle.bat" else "gradle"
        val synced = syncGradleHome?.let { Path.of(it, "bin", name) }
        return if (synced != null && synced.isRegularFile()) synced.toString() else name
    }

    /**
     * Publishes the model as Gradle declares it first, then republishes it after the sync tasks have generated their
     * sources, so the analyzer does not wait for code generation before it can resolve the project's dependencies.
     */
    private fun importWorkspace(
        project: Project,
        virtualFileUrlManager: VirtualFileUrlManager,
        watermark: Long,
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
        syncJavaHome = jdkToUse

        // The models are handed over with `trySend` (the channel is unbounded): the Tooling API calls below are
        // blocking and run inside non-suspending lambdas.
        try {
            connection.use { projectConnection ->
                withDaemonInitScripts { daemonInitScripts ->
                    val metadata = executeGradleSync(projectConnection, channel, daemonInitScripts, jdkToUse)
                    syncGradleHome = metadata.gradleHome
                    channel.trySend(
                        ImportEvent.UpdateWorkspaceModel(
                            toStorage(
                                metadata,
                                virtualFileUrlManager,
                                channel,
                                jdkToUse
                            ),
                            watermark,
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
