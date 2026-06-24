// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.codeLens

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.codeLens.LSCodeLensProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Range
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Base class for the [LSCodeLensProvider]s that surface ▶ Run / 🐞 Debug lenses above each JVM `main` entry point in a file.
 */
abstract class LSJvmRunMainCodeLensProvider : LSCodeLensProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    final override fun getCodeLenses(params: CodeLensParams): Flow<CodeLens> = flow {
        val lenses: List<CodeLens> = server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.uri.uri.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                findMainEntryPoints(psiFile).flatMap { (range, mainClass) ->
                    listOf(
                        launchLens(range, mainClass, noDebug = true, title = "$(play) Run"),
                        launchLens(range, mainClass, noDebug = false, title = "$(debug-alt) Debug"),
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

    private fun launchLens(range: Range, mainClass: String, noDebug: Boolean, title: String): CodeLens {
        val command = Command(
            title = title,
            command = RUN_COMMAND_NAME,
            arguments = listOf(
                buildJsonObject {
                    put("mainClass", mainClass)
                    put("noDebug", noDebug)
                }
            ),
        )
        return CodeLens(range, command, data = null)
    }

    private companion object {
        const val RUN_COMMAND_NAME: String = "intellij_debugger.runMain"
    }
}
