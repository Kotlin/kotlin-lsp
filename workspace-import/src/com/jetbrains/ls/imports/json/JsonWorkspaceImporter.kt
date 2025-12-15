// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

object JsonWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null
        val jsonPath = projectDirectory / "workspace.json"
        return importWorkspaceJson(
            jsonPath, projectDirectory, onUnresolvedDependency, virtualFileUrlManager)
    }

    fun importWorkspaceJson(
        file: Path,
        projectDirectory: Path,
        onUnresolvedDependency: (String) -> Unit,
        virtualFileUrlManager: VirtualFileUrlManager
    ): MutableEntityStorage {
        val workspaceJson: WorkspaceData = try {
            file.inputStream().use { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream(stream)
            }
        } catch (e: SerializationException) {
            throw WorkspaceImportException(
                "Error parsing workspace.json",
                "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
                e
            )
        }
        val workspaceData = WorkspaceModelProcessorForTests.process(workspaceJson)
        workspaceData.modules.forEach { module ->
            module.dependencies
                .filterIsInstance<DependencyData.Library>()
                .filter { it.name != "JDK" }
                .filter { dependency -> workspaceData.libraries.none { it.name == dependency.name } }
                .forEach { onUnresolvedDependency(it.name.removePrefix("Gradle: ")) }
        }
        workspaceData.libraries.forEach { library ->
            if (library.roots.any { toAbsolutePath(it.path, projectDirectory).notExists() }) {
                onUnresolvedDependency(library.name.removePrefix("Gradle: "))
            }
        }
        return workspaceModel(
            workspaceData,
            projectDirectory,
            WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
            virtualFileUrlManager
        )
    }

    private fun isApplicableDirectory(projectDirectory: Path): Boolean =
        (projectDirectory / "workspace.json").exists()

    interface WorkspaceModelProcessorForTests {
        fun resolveMissingLibrary(libraryName: String): LibraryData?

        fun processKotlinSettings(settings: KotlinSettingsData): KotlinSettingsData

        companion object {
            val current: AtomicReference<WorkspaceModelProcessorForTests?> = AtomicReference(null)

            fun process(data: WorkspaceData): WorkspaceData {
                val processor = current.get() ?: return data

                return data.copy(
                    libraries = data.libraries + processor.getAdditionalLibraries(data),
                    kotlinSettings = data.kotlinSettings.map { processor.processKotlinSettings(it) }
                )
            }

            private fun WorkspaceModelProcessorForTests.getAdditionalLibraries(data: WorkspaceData): List<LibraryData> {
                val libraryNames = data.libraries.mapTo(mutableSetOf()) { it.name }
                val missingLibraries = buildSet {
                    for (module in data.modules) {
                        for (dependency in module.dependencies) {
                            if (dependency is DependencyData.Library && dependency.name !in libraryNames) {
                                add(dependency.name)
                            }
                        }
                    }
                }
                return missingLibraries.mapNotNull { resolveMissingLibrary(it) }
            }

            @TestOnly
            inline fun <R> withProcessor(resolver: WorkspaceModelProcessorForTests, action: () -> R): R {
                try {
                    if (!current.compareAndSet(null, resolver)) {
                        error("AdditionalLibrariesResolverForTests is already set")
                    }
                    return action()
                } finally {
                    if (!current.compareAndSet(resolver, null)) {
                        error("AdditionalLibrariesResolverForTests is not set")
                    }
                }
            }
        }
    }
}