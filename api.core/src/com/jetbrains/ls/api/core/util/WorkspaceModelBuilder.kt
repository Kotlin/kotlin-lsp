// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.openapi.projectRoots.SdkType
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.workspaceStructure
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.WorkspaceFolder
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

interface WorkspaceModelBuilder {
    fun addSdk(sdk: LSSdk)
    fun addFolder(workspaceFolder: WorkspaceFolder)
    fun addLibrary(library: LSLibrary)
    fun removeFolder(workspaceFolder: WorkspaceFolder)
    fun removeLibrary(libraryName: String)
    fun clearAll()
}

context(_: LSServer)
suspend fun updateWorkspaceModel(updater: WorkspaceModelBuilder.() -> Unit) {
    var sdkToAdd: LSSdk? = null
    val libs = mutableListOf<LSLibrary>()
    val dirs = mutableListOf<WorkspaceFolder>()
    val foldersToRemove = mutableListOf<WorkspaceFolder>()
    val librariesToRemove = mutableListOf<String>()
    var shouldClearAll = false

    val builder = object : WorkspaceModelBuilder {
        override fun addSdk(sdk: LSSdk) {
            sdkToAdd = sdk
        }

        override fun addFolder(workspaceFolder: WorkspaceFolder) {
            dirs.add(workspaceFolder)
        }

        override fun addLibrary(library: LSLibrary) {
            libs.add(library)
        }

        override fun removeFolder(workspaceFolder: WorkspaceFolder) {
            foldersToRemove.add(workspaceFolder)
        }

        override fun removeLibrary(libraryName: String) {
            librariesToRemove.add(libraryName)
        }

        override fun clearAll() {
            shouldClearAll = true
        }
    }

    updater(builder)

    workspaceStructure.updateWorkspaceModelDirectly { urlManager, storage ->
        val source = object : EntitySource {}

        if (shouldClearAll) {
            // Remove all existing content roots and libraries
            storage.entities<ContentRootEntity>().forEach { storage.removeEntity(it) }
            storage.entities<LibraryEntity>().forEach { storage.removeEntity(it) }
        }

        // Remove specific folders
        for (folderToRemove in foldersToRemove) {
            val folderUrl = urlManager.getOrCreateFromUrl(folderToRemove.uri.lspUriToIntellijUri()!!)
            storage.entities<ContentRootEntity>()
                .filter { it.url == folderUrl }
                .forEach { storage.removeEntity(it) }
        }

        // Remove specific libraries
        for (libraryToRemove in librariesToRemove) {
            storage.entities<LibraryEntity>()
                .filter { it.name == libraryToRemove }
                .forEach { storage.removeEntity(it) }
        }

        // Add new libraries
        val libEntities = libs.map { lib ->
            storage addEntity LibraryEntity(
                name = lib.name,
                tableId = LibraryTableId.ProjectLibraryTableId,
                roots = lib.roots.map { root ->
                    LibraryRoot(
                        url = urlManager.getOrCreateFromUrl(root.lspUriToIntellijUri()!!),
                        type = LibraryRootTypeId.COMPILED
                    )
                },
                entitySource = source,
            )
        }

        val newContentRoots = dirs.map { workspaceFolder ->
            val folderUrl = urlManager.getOrCreateFromUrl(workspaceFolder.uri.lspUriToIntellijUri()!!)
            ContentRootEntity(
                url = folderUrl,
                excludedPatterns = emptyList(),
                entitySource = source,
            ) {
                sourceRoots = listOf(
                    SourceRootEntity(
                        url = folderUrl,
                        rootTypeId = SourceRootTypeId("java-source"),
                        entitySource = source,
                    ) {
                        this.contentRoot = ContentRootEntity(folderUrl, listOf(), entitySource) {
                            excludedUrls = listOf(
                                ExcludeUrlEntity(
                                    urlManager.getOrCreateFromUrl(workspaceFolder.uri.lspUriToIntellijUri() + "/build"),
                                    entitySource
                                ),
                                ExcludeUrlEntity(
                                    urlManager.getOrCreateFromUrl(workspaceFolder.uri.lspUriToIntellijUri() + "/target"),
                                    entitySource
                                ),
                            )
                        }
                    }
                )
            }
        }

        val mainModule = storage.entities<ModuleEntity>().singleOrNull() ?: run {
            storage addEntity ModuleEntity(
                name = "main",
                dependencies = listOf<ModuleDependencyItem>(ModuleSourceDependency),
                entitySource = source,
            )
        }

        storage.modifyModuleEntity(mainModule) {
            // Only add new library dependencies, don't duplicate existing ones
            val existingLibNames = dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }.toSet()
            val newLibDependencies = libEntities
                .filter { it.name !in existingLibNames }
                .map { libEntity ->
                    LibraryDependency(
                        library = libEntity.symbolicId,
                        exported = false,
                        scope = DependencyScope.COMPILE
                    )
                }
            dependencies += newLibDependencies
            contentRoots += newContentRoots
        }

        sdkToAdd?.let {
            addSdk(
                name = it.name,
                type = it.type,
                roots = it.roots,
                urlManager = urlManager,
                source = source,
                storage = storage
            )
        }
    }
}

data class LSLibrary(
    val roots: List<URI>,
    val name: String,
)

data class LSSdk(
    val roots: List<URI>,
    val name: String,
    val type: SdkType) {
}

fun jarLibraries(directory: Path): List<LSLibrary> =
    directory
        .listDirectoryEntries()
        .filter { it.extension == "jar" }
        .map { library ->
            LSLibrary(
                roots = listOf(library.toLspUri()),
                name = library.nameWithoutExtension,
            )
        }
