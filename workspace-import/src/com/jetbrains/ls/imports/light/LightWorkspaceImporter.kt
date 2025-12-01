// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.light

import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.jetbrains.ls.api.core.util.UriConverter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.utils.applyChangesWithDeduplication
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Builds a minimal workspace model for projects without a recognized build system.
 * Mirrors the logic of initLightEditMode but operates on a fresh MutableEntityStorage and does not use updateWorkspaceModel.
 */
object LightWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit
    ): EntityStorage? =
        createLightWorkspace(projectDirectory, virtualFileUrlManager)

    fun createEmptyWorkspace(virtualFileUrlManager: VirtualFileUrlManager, storage: MutableEntityStorage) {
        val model = createLightWorkspace(null, virtualFileUrlManager)
        applyChangesWithDeduplication(storage, model)
    }

    private fun createLightWorkspace(
        projectDirectory: Path?,
        virtualFileUrlManager: VirtualFileUrlManager
    ): MutableEntityStorage {
        val storage = MutableEntityStorage.create()
        val entitySource = object : EntitySource {}

        val stdlibUrl = UriConverter.localAbsolutePathToIntellijUri(getKotlinStdlibPath())
        val stdlibSourcesUrl = getKotlinStdlibSourcesPath()?.let { UriConverter.localAbsolutePathToIntellijUri(it) }
        storage addEntity LibraryEntity(
            name = "stdlib",
            tableId = LibraryTableId.ProjectLibraryTableId,
            roots = listOfNotNull(
                LibraryRoot(virtualFileUrlManager.getOrCreateFromUrl(stdlibUrl), LibraryRootTypeId.COMPILED),
                stdlibSourcesUrl?.let { LibraryRoot(virtualFileUrlManager.getOrCreateFromUrl(it), LibraryRootTypeId.SOURCES) }
            ),
            entitySource = entitySource
        )

        val moduleBuilder = ModuleEntity(
            name = "main",
            dependencies = listOf(ModuleSourceDependency, InheritedSdkDependency),
            entitySource = entitySource,
        )
        val theModule = storage addEntity moduleBuilder

        if (projectDirectory != null) {
            val contentRootUrl = projectDirectory.toVirtualFileUrl(virtualFileUrlManager)
            val contentRoot = ContentRootEntity(
                url = contentRootUrl,
                excludedPatterns = emptyList(),
                entitySource = entitySource
            ) {
                excludedUrls = listOf(
                    ExcludeUrlEntity(virtualFileUrlManager.getOrCreateFromUrl(contentRootUrl.url + "/build"), entitySource),
                    ExcludeUrlEntity(virtualFileUrlManager.getOrCreateFromUrl(contentRootUrl.url + "/target"), entitySource),
                    ExcludeUrlEntity(virtualFileUrlManager.getOrCreateFromUrl(contentRootUrl.url + "/libraries"), entitySource)
                )
                sourceRoots = listOf(
                    SourceRootEntity(
                        url = contentRootUrl,
                        rootTypeId = SourceRootTypeId("java-source"),
                        entitySource = entitySource
                    ) {
                        this.contentRoot = this@ContentRootEntity
                    }
                )
                module = moduleBuilder
            }
            storage addEntity contentRoot
            storage.modifyModuleEntity(theModule) {
                contentRoots += contentRoot
            }

            val librariesDir = projectDirectory / "libraries"
            if (librariesDir.isDirectory()) {
                librariesDir.listDirectoryEntries().filter { it.extension == "jar" }.forEach { jar ->
                    val jarUrl = UriConverter.localAbsolutePathToIntellijUri(jar.absolutePathString())
                    storage addEntity LibraryEntity(
                        name = jar.absolutePathString(),
                        tableId = LibraryTableId.ProjectLibraryTableId,
                        roots = listOf(
                            LibraryRoot(
                                url = virtualFileUrlManager.getOrCreateFromUrl(jarUrl),
                                type = LibraryRootTypeId.COMPILED
                            )
                        ),
                        entitySource = entitySource
                    )
                }
            }
            storage.modifyModuleEntity(theModule) {
                dependencies += storage.entities<LibraryEntity>()
                    .map { libEntity ->
                        LibraryDependency(
                            library = libEntity.symbolicId,
                            exported = false,
                            scope = DependencyScope.COMPILE
                        )
                    }
            }
        }

        return storage
    }

    private fun getKotlinStdlibPath(): String =
        when {
            isRunningFromSources -> KotlinArtifacts.kotlinStdlib.absolutePath
            else -> PathUtil.getJarPathForClass(Sequence::class.java)
        }

    private fun getKotlinStdlibSourcesPath(): String? =
        when {
            isRunningFromSources -> KotlinArtifacts.kotlinStdlibSources.absolutePath
            else -> null // LSP-224 TODO we should bundle the sources jar
        }
}