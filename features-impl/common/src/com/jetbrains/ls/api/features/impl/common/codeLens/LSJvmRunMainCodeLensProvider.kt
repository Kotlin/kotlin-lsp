// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.codeLens

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.intellij.workspaceModel.ide.legacyBridge.findModuleEntity
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.launch.runTools
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeLens.LSCodeLensProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Range
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.VisibleForTesting

@VisibleForTesting
@Serializable
data class RunMainArgs(
    val mainClass: String,
    val uri: DocumentUri,
    val noDebug: Boolean,
    /**
     * The registration id of the build tool that imported the module of [uri] (`"gradle"`, `"maven"`, …), or
     * `null` for a module without one. The client picks the launch configuration type from it, without a request.
     */
    val tool: String? = null,
)

/**
 * Base class for the [LSCodeLensProvider]s that surface ▶ Run / 🐞 Debug lenses above each JVM `main` entry point in a file.
 */
abstract class LSJvmRunMainCodeLensProvider : LSCodeLensProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    final override fun getCodeLenses(params: CodeLensParams): Flow<CodeLens> = flow {
        if (!server.config.clientSupportsRunMainCodeLens) return@flow
        val lenses: List<CodeLens> = server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.uri.uri.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val tool = ModuleUtilCore.findModuleForFile(virtualFile, project)?.findModuleEntity()?.let { project.runTools?.toolTypeFor(it) }
                findMainEntryPoints(psiFile).flatMap { (range, mainClass) ->
                    listOf(
                        launchLens(params.textDocument.uri, range, mainClass, tool, noDebug = true, title = LspServerBundle.message("command.play.run")),
                        launchLens(params.textDocument.uri, range, mainClass, tool, noDebug = false, title = LspServerBundle.message("command.debug.debug")),
                    )
                }
            }
        }
        emitAll(lenses.asFlow())
    }

    /**
     * Finds the runnable `main` entry points in [psiFile]. For each one, returns the range to anchor
     * the lens on (typically the `main` declaration) and the JVM class name a run configuration would
     * launch. Invoked under a read action inside the analysis context.
     */
    protected abstract fun findMainEntryPoints(psiFile: PsiFile): List<MainEntryPoint>

    protected data class MainEntryPoint(val range: Range, val mainClass: String)

    private fun launchLens(uri: DocumentUri, range: Range, mainClass: String, tool: String?, noDebug: Boolean, title: @Nls String): CodeLens {
        val command = Command(
            title = title,
            command = RUN_COMMAND_NAME,
            arguments = listOf(LSP.json.encodeToJsonElement(RunMainArgs(mainClass = mainClass, uri = uri, noDebug = noDebug, tool = tool))),
        )
        return CodeLens(range, command, data = null)
    }

    private companion object {
        /**
         * The client-side command the lens invokes. Named for the JVM rather than for one launch configuration
         * type: the lens carries the tool of the module, and the client picks the configuration type from it
         * (see `dap.ts`).
         */
        const val RUN_COMMAND_NAME: String = "intellij.jvm.runMain"
    }
}
