// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.util.PathUtil
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
    addSdk(LSSdk(
        name = "Java SDK",
        type = JavaSdk.getInstance(),
        roots = javaHome()
    ))
}

fun WorkspaceModelBuilder.addKotlinStdlib() {
    addLibrary(
        LSLibrary(
            binaryRoots = listOf(getKotlinStdlibPath().toLspUri()),
            sourceRoots = listOfNotNull(getKotlinStdlibSourcesPath()?.toLspUri()),
            name = "stdlib"
        )
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

fun getKotlinStdlibSourcesPath(): Path? {
    return when {
        isRunningFromSources -> {
            // get kotlin stdlib used for monorepo compilation
            KotlinArtifacts.kotlinStdlibSources.toPath()
        }

        else -> {
           null // LSP-224 TODO we should bundle the sources jar
        }
    }
}


fun WorkspaceModelBuilder.registerStdlibAndJdk() {
    addKotlinStdlib()
    addSdk( LSSdk(roots = javaHome(), name = "Java SDK", type = JavaSdk.getInstance()))
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
            addSdk(
                name = "Java SDK",
                type = JavaSdk.getInstance(),
                roots = javaHome(),
                urlManager = virtualFileUrlManager,
                source = WorkspaceEntitySource(folderPath.toVirtualFileUrl(virtualFileUrlManager)),
                storage = imported
            )
        }
        storage.applyChangesFrom(imported)
    }
    lspClient.reportProgressMessage(params, "Project is indexed")
}