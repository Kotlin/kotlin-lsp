// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.customName
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.*
import com.jetbrains.ls.api.core.workspaceStructure
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.reportProgressMessage
import com.jetbrains.lsp.protocol.*
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

fun WorkspaceModelBuilder.addJdkFromJavaHome() {
    addLibrary(LSLibrary(javaHome(), name = "jdk"))
}

fun WorkspaceModelBuilder.addKotlinStdlib() {
    addLibrary(
        LSLibrary(
            listOf(
               getKotlinStdlibPath().toLspUri(),
            ), name = "stdlib"
        )
    )
}

internal fun createDefaultJDKEntity(source: EntitySource, virtualFileUrlManager: VirtualFileUrlManager): SdkEntity.Builder {
    return SdkEntity(
        name = "Java SDK",
        type = JavaSdk.getInstance().name,
        roots = javaHome().map { root ->
            SdkRoot(
                virtualFileUrlManager.getOrCreateFromUrl(root.lspUriToIntellijUri()!!),
                SdkRootTypeId(OrderRootType.CLASSES.customName),
            )
        },
        additionalData = "",
        entitySource = source
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

fun WorkspaceModelBuilder.registerStdlibAndJdk() {
    addKotlinStdlib()
    addJdkFromJavaHome()
}

fun javaHome(): List<URI> {
    val javaHome = System.getProperty("java.home")
    val path = Paths.get(javaHome)
    require(path.isDirectory()) { "Expected a directory, got $path" }
    return JavaSdkImpl.findClasses(path, false).map { (it.replace("!/", "!/modules/").intellijUriToLspUri()) }
}

context(_: LSServer, _: LspHandlerContext)
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
        val noSdk = imported.entities(SdkEntity::class.java).firstOrNull() == null
        if (noSdk) {
            addDefaultJavaSDK(folderPath, virtualFileUrlManager, imported)
        }
        storage.applyChangesFrom(imported)
        lspClient.reportProgressMessage(params, "Indexing project")
    }
    lspClient.reportProgressMessage(params, "Project is indexed")
}

private fun addDefaultJavaSDK(
    folderPath: Path,
    virtualFileUrlManager: VirtualFileUrlManager,
    imported: MutableEntityStorage,
) {
    val entitySource = WorkspaceEntitySource(folderPath.toVirtualFileUrl(virtualFileUrlManager))

    val jdk = imported addEntity createDefaultJDKEntity(
        entitySource,
        virtualFileUrlManager
    )

    for (module in imported.entities<ModuleEntity>()) {
        imported.modifyModuleEntity(module) {
            dependencies.firstOrNull { it is SdkDependency || it is InheritedSdkDependency }
                ?.let { dependencies -= it }

            dependencies += SdkDependency(jdk.symbolicId)
        }
    }
}