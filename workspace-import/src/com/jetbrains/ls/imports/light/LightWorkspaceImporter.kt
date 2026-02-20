// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.light

import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.jetbrains.ls.api.core.util.UriConverter
import com.jetbrains.ls.imports.api.EmptyWorkspaceImporter
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Builds a minimal workspace model for projects without a recognized build system.
 * Mirrors the logic of initLightEditMode but operates on a fresh MutableEntityStorage and does not use updateWorkspaceModel.
 */
object LightWorkspaceImporter : EmptyWorkspaceImporter {
    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit
    ): EntityStorage {
        return createLightWorkspace(projectDirectory, virtualFileUrlManager, defaultSdkPath)
    }

    override fun createEmptyWorkspace(
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager
    ): EntityStorage {
        return createLightWorkspace(null, virtualFileUrlManager, defaultSdkPath)
    }

    private fun createLightWorkspace(
        projectDirectory: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        defaultSdkPath: Path?
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
        storage.fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
        return storage
    }

    private fun getKotlinStdlibPath(): String =
        when {
            isRunningFromSources -> KotlinArtifacts.kotlinStdlibPath.absolutePathString()
            else -> PathUtil.getJarPathForClass(Sequence::class.java)
        }

    private fun getKotlinStdlibSourcesPath(): String? =
        when {
            isRunningFromSources -> KotlinArtifacts.kotlinStdlibSourcesPath.absolutePathString()
            else -> null // LSP-224 TODO we should bundle the sources jar
        }
}