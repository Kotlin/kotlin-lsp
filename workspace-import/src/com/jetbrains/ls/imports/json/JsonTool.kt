// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.ImportRequest
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.ToolFileListener
import com.jetbrains.ls.imports.api.modifiedSince
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.utils.LsImportBundle
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

/**
 * The external-system id the JSON importer marks the modules it imports with.
 *
 * It names the *file format* the model arrived in, not a build system — which is why the export deliberately does not
 * record it as [WorkspaceData.externalSystem]: a `workspace.json` says which build system produced the model it holds,
 * and re-reading that file must not overwrite the answer with the name of the file itself.
 */
const val JSON_EXTERNAL_SYSTEM_ID: String = "JSON"

private val LOG = logger<JsonTool>()

/** The tool's own ask; it carries no data, the import always re-reads the whole file. */
private object JsonReimport : ImportRequest

/** One folder's live JSON build tool; [JsonDriver] starts it. */
class JsonTool(
    toolContext: BuildToolDriverContext,
    private val parameters: WorkspaceImportParameters,
) : BuildTool {

    /** The one input of this import. */
    private val workspaceJson: Path = parameters.projectDirectory / "workspace.json"

    /** The wall-clock start of the last sync that imported; [workspaceJsonSeen] is whether it saw the file. */
    @Volatile
    private var lastSyncStartedAt: Long? = null

    @Volatile
    private var workspaceJsonSeen: Boolean = false

    private val reimportRequestsFlow = MutableSharedFlow<ImportRequest>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** This tool's own asks; the platform debounces them into reload cycles. */
    override val reimportRequests: Flow<ImportRequest> get() = reimportRequestsFlow

    /** The tool's own watching: `workspace.json` is the whole input, so only its changes ask for a re-import. */
    private val watchListener = object : ToolFileListener {
        override fun changed(path: Path) {
            if (path != workspaceJson) return
            LOG.info("The workspace description changed: $workspaceJson")
            reimportRequestsFlow.tryEmit(JsonReimport)
        }

        override fun lost() {
            LOG.info("The file watcher lost changes, so $workspaceJson may have changed too")
            reimportRequestsFlow.tryEmit(JsonReimport)
        }
    }

    init {
        // `workspace.json` is a direct child of the project directory, which indexing does not watch on its own.
        toolContext.watcher.watch(parameters.projectDirectory, watchListener)
    }

    /** Whether the one input changed on disk after [since]: modified, appeared, or deleted since the last import. */
    private fun inputChangedSince(since: Long?): Boolean {
        if (since == null) return true
        return workspaceJson.modifiedSince(since) || workspaceJsonSeen != workspaceJson.exists()
    }

    override fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent> = channelFlow {
        val startedAt = System.currentTimeMillis()
        if (!request.force && request.toolRequest == null && !inputChangedSince(lastSyncStartedAt)) {
            send(ImportEvent.WorkspaceModelNotChanged(request.targetWatermark))
            return@channelFlow
        }
        // The baseline is taken before the import reads its input, so a change landing while it runs
        // reads as changed at the next judgment.
        lastSyncStartedAt = startedAt
        workspaceJsonSeen = workspaceJson.exists()
        val progress = object : WorkspaceImportProgressReporter {
            override fun onUnresolvedDependency(depName: String) { trySend(ImportEvent.UnresolvedDependency(depName)) }
            override fun onStdOutput(line: String) { trySend(ImportEvent.StdOutput(line)) }
            override fun onErrorOutput(line: String) { trySend(ImportEvent.ErrorOutput(line)) }
            override fun progressStatus(text: String) { trySend(ImportEvent.ProgressStatus(text)) }
        }
        try {
            context.withProject {
                importWorkspace(context.virtualFileUrlManager, progress)
                    ?.let { send(ImportEvent.UpdateWorkspaceModel(it, request.targetWatermark)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            send(ImportEvent.Failed(e))
        }
    }.buffer(Channel.UNLIMITED)



    private fun importWorkspace(
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter,
    ): EntityStorage? {
        val projectDirectory = parameters.projectDirectory
        val jsonPath = projectDirectory / "workspace.json"
        if (jsonPath.notExists()) return null
        return importWorkspaceJson(
            jsonPath, projectDirectory, parameters.defaultSdkPath, virtualFileUrlManager, progress
        )
    }
}

fun importWorkspaceJson(
    file: Path,
    projectDirectory: Path,
    defaultSdkPath: Path?,
    virtualFileUrlManager: VirtualFileUrlManager,
    progress: WorkspaceImportProgressReporter
): EntityStorage {
    val workspaceJson: WorkspaceData = try {
        file.inputStream().use { stream ->
            @OptIn(ExperimentalSerializationApi::class)
            Json.decodeFromStream(stream)
        }
    } catch (e: SerializationException) {
        throw WorkspaceImportException(
            LsImportBundle.message("error.parsing.workspace.json"),
            "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
            e
        )
    }
    return MutableEntityStorage.create().apply {
        importWorkspaceData(
            postProcessWorkspaceData(
                workspaceJson,
                projectDirectory,
                progress::onUnresolvedDependency
            ),
            projectDirectory,
            WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
            virtualFileUrlManager, false, JSON_EXTERNAL_SYSTEM_ID
        )
        fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
    }
}

fun postProcessWorkspaceData(
    workspaceData: WorkspaceData,
    projectDirectory: Path,
    onUnresolvedDependency: (String) -> Unit,
): WorkspaceData {
    val reportUnresolvedName: (String) -> Unit = { name ->
        onUnresolvedDependency(name.removeSuffix("Gradle: ").removeSuffix("Maven: "))
    }
    workspaceData.modules.forEach { module ->
        module.dependencies
            .filterIsInstance<DependencyData.Library>()
            .filter { it.name != "JDK" }
            .filter { dependency -> workspaceData.libraries.none { it.name == dependency.name } }
            .forEach { reportUnresolvedName(it.name) }
    }
    workspaceData.libraries.forEach { library ->
        if (library.roots.any { toAbsolutePath(it.path, projectDirectory).notExists() }) {
            reportUnresolvedName(library.name)
        }
    }
    return workspaceData
}
