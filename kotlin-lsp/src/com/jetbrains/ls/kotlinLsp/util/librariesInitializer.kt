// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.LSLibrary
import com.jetbrains.ls.api.core.util.WorkspaceModelBuilder
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import com.jetbrains.ls.api.core.util.lspUriToIntellijUri
import com.jetbrains.ls.api.core.util.toLspUri
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.reportProgressMessage
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.ShowMessageNotification
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.WorkDoneProgressParams
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

context(WorkspaceModelBuilder)
fun addJdkFromJavaHome() {
    addLibrary(LSLibrary(javaHome(), name = "jdk"))
}

context(WorkspaceModelBuilder)
fun addKotlinStdlib() {
    addLibrary(
        LSLibrary(
            listOf(
               getKotlinStdlibPath().toLspUri(),
            ), name = "stdlib"
        )
    )
}


// TODO LSP-149 should be a real jdk entity
internal fun createJDKEntity(source: EntitySource, virtualFileUrlManager: VirtualFileUrlManager): LibraryEntity.Builder {
    return LibraryEntity(
        name = "JDK",
        tableId = LibraryTableId.ProjectLibraryTableId,
        roots = javaHome().map { root ->
            LibraryRoot(
                url = virtualFileUrlManager.getOrCreateFromUrl(root.lspUriToIntellijUri()),
                type = LibraryRootTypeId.COMPILED
            )
        },
        entitySource = source,
    )
}

fun getKotlinStdlibPath(): Path {
    return when {
        isRunningFromSources -> {
            // get kotlin stdlib used for monorepo compilation
            KotlinArtifacts.kotlinStdlib.toPath()
        }

        else -> {
            // get kotlin stdlib from the current classpath
            val classFromStdlibButNotInBuiltins = Sequence::class.java
            val stdlibJar = PathUtil.getJarPathForClass(classFromStdlibButNotInBuiltins)
            stdlibJar.toNioPathOrNull()
                ?: error("Failed to find kotlin stdlib jar in $stdlibJar")
        }
    }
}

context(WorkspaceModelBuilder)
fun registerStdlibAndJdk() {
    addKotlinStdlib()
    addJdkFromJavaHome()
}

fun javaHome(): List<URI> {
    val javaHome = System.getProperty("java.home")
    val path = Paths.get(javaHome)
    require(path.isDirectory()) { "Expected a directory, got $path" }
    return JavaSdkImpl.findClasses(path, false).map { (it.replace("!/", "!/modules/").intellijUriToLspUri()) }
}

context(LSServer, LspHandlerContext)
suspend fun importProject(
    folderPath: Path,
    importer: WorkspaceImporter,
    params: WorkDoneProgressParams,
) {
    val unresolved = mutableSetOf<String>()
    workspaceStructure.updateWorkspaceModelDirectly { virtualFileUrlManager, storage ->
        val imported = importer.importWorkspace(folderPath, virtualFileUrlManager, unresolved::add)
        if (unresolved.isNotEmpty()) {
            lspClient.notify(
                ShowMessageNotification,
                ShowMessageParams(MessageType.Warning, unresolved.joinToString(", ", "Couldn't resolve some dependencies: ")),
            )
        }
        addFakeJdKLibrary(folderPath, virtualFileUrlManager, imported)
        storage.applyChangesFrom(imported)
        lspClient.reportProgressMessage(params, "Indexing project")
    }
    lspClient.reportProgressMessage(params, "Project is indexed")
}

private fun addFakeJdKLibrary(
    folderPath: Path,
    virtualFileUrlManager: VirtualFileUrlManager,
    imported: MutableEntityStorage,
) {
    val entitySource = WorkspaceEntitySource(folderPath.toVirtualFileUrl(virtualFileUrlManager))

    val jdk = imported addEntity createJDKEntity(
        entitySource,
        virtualFileUrlManager
    )

    for (module in imported.entities<ModuleEntity>()) {
        imported.modifyModuleEntity(module) {
            dependencies.firstOrNull { it is SdkDependency || it is InheritedSdkDependency }
                ?.let { dependencies -= it }

            dependencies += LibraryDependency(jdk.symbolicId, exported = false, scope = DependencyScope.COMPILE)
        }
    }
}