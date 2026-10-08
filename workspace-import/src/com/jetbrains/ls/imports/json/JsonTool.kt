// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.utils.LsImportBundle
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.nio.file.Path
import kotlin.io.path.div
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

/** One folder's live JSON build tool; [JsonDriver] starts it. */
class JsonTool(
    toolContext: BuildToolDriverContext,
    private val parameters: WorkspaceImportParameters,
) : BuildTool {

    init {
        // `workspace.json` is a direct child of the project directory, which indexing does not watch on its own.
        toolContext.watcher.watch(parameters.projectDirectory)
    }

    override fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent> = channelFlow {
        val progress = object : WorkspaceImportProgressReporter {
            override fun onUnresolvedDependency(depName: String) { trySend(ImportEvent.UnresolvedDependency(depName)) }
            override fun onStdOutput(line: String) { trySend(ImportEvent.StdOutput(line)) }
            override fun onErrorOutput(line: String) { trySend(ImportEvent.ErrorOutput(line)) }
            override fun progressStatus(text: String) { trySend(ImportEvent.ProgressStatus(text)) }
        }
        try {
            importWorkspace(context.virtualFileUrlManager, progress)
                ?.let { send(ImportEvent.UpdateWorkspaceModel(it, request.targetWatermark)) }
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
