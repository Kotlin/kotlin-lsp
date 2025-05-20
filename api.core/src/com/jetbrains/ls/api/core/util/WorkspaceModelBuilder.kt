package com.jetbrains.ls.api.core.util

import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.WorkspaceFolder
import com.jetbrains.lsp.protocol.asURI
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

interface WorkspaceModelBuilder {
    fun addFolder(workspaceFolder: WorkspaceFolder)
    fun addLibrary(library: LSLibrary)
}

context(LSServer)
suspend fun updateWorkspaceModel(updater: context(WorkspaceModelBuilder) () -> Unit) {
    val libs = mutableListOf<LSLibrary>()
    val dirs = mutableListOf<WorkspaceFolder>()

    val builder = object : WorkspaceModelBuilder {
        override fun addFolder(workspaceFolder: WorkspaceFolder) {
            dirs.add(workspaceFolder)
        }

        override fun addLibrary(library: LSLibrary) {
            libs.add(library)
        }
    }

    updater(builder)

    workspaceStructure.updateWorkspaceModelDirectly { urlManager, storage ->
        val source = object : EntitySource {}

        val libEntities = libs.map { lib ->
            storage addEntity LibraryEntity(
                name = lib.name,
                tableId = LibraryTableId.ProjectLibraryTableId,
                roots = lib.roots.map { root ->
                    LibraryRoot(
                        url = urlManager.getOrCreateFromUrl(root.lspUriToIntellijUri()),
                        type = LibraryRootTypeId.COMPILED
                    )
                },
                entitySource = source,
            )
        }

        val newContentRoots = dirs.map { workspaceFolder ->
            val folderUrl = urlManager.getOrCreateFromUrl(workspaceFolder.uri.lspUriToIntellijUri())
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
            dependencies += libEntities.map { libEntity ->
                LibraryDependency(
                    library = libEntity.symbolicId,
                    exported = false,
                    scope = DependencyScope.COMPILE
                )
            }
            contentRoots += newContentRoots
        }
    }    
}

data class LSLibrary(
    val roots: List<URI>,
    val name: String,
)

fun jarLibraries(directory: Path): List<LSLibrary> =
    directory
        .listDirectoryEntries()
        .filter { it.extension == "jar" }
        .map { library ->
            LSLibrary(
                roots = listOf(library.asURI(URI.Schemas.JAR)),
                name = library.nameWithoutExtension,
            )
        }
