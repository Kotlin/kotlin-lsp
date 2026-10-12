// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.hover

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsFindInjectedFileAt
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams

object LSHover {
    /** Asks the providers of the language injected at the position first, as their targets are what the host providers cannot render. */
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getHover(params: HoverParams): Hover? {
        val hostProviders = configuration.entriesFor<LSHoverProvider>(params.textDocument)
        val injectedProviders = injectedLanguageAt(params)?.let { configuration.entriesFor<LSHoverProvider>(it) }.orEmpty()
        return (injectedProviders + hostProviders).distinct().firstNotNullOfOrNull { it.getHover(params) }
    }

    context(configuration: LSConfiguration, server: LSServer)
    private suspend fun injectedLanguageAt(params: HoverParams): LSLanguage? = server.withAnalysisContext {
        readAction {
            val virtualFile = params.findVirtualFile() ?: return@readAction null
            val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
            val document = virtualFile.findDocument() ?: return@readAction null
            val (injectedFile, _) = lsFindInjectedFileAt(psiFile, document.offsetByPosition(params.position)) ?: return@readAction null
            configuration.languageFor(injectedFile.psiFile.language)
        }
    }
}
