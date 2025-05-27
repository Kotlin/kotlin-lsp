// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.codeActions

import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.document.LSDocumentCommandExecutor
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

abstract class LSSimpleCodeActionProvider<P : Any> : LSCodeActionProvider, LSCommandDescriptorProvider {
    protected abstract val title: String
    protected open val kind: CodeActionKind? = null
    protected open val isPreferred: Boolean? = null
    protected open val commandName: String get() = title

    abstract val dataSerializer: KSerializer<P>

    context(LSServer, LSAnalysisContext)
    abstract fun getData(file: VirtualFile, params: CodeActionParams): P?

    context(LSServer, LSAnalysisContext)
    abstract fun execute(file: VirtualFile, data: P): List<TextEdit>

    context(LSServer)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val params = withAnalysisContext {
            val file = documentUri.findVirtualFile() ?: return@withAnalysisContext null
            getData(file, params)
        } ?: return@flow
        val arguments = buildList {
            add(LSP.json.encodeToJsonElement(documentUri))
            if (params != NoData) {
                add(LSP.json.encodeToJsonElement(dataSerializer, params))
            }
        }
        val action = CodeAction(
            title = title,
            kind = kind,
            isPreferred = isPreferred,
            command = Command(
                title = title,
                command = commandName,
                arguments = arguments,
            ),
        )
        emit(action)
    }

    @Serializable
    data object NoData

    override val commandDescriptors: List<LSCommandDescriptor> =
        listOf(LSCommandDescriptor(title, commandName, LSSimpleDocumentCommandExecutor()))

    internal inner class LSSimpleDocumentCommandExecutor : LSDocumentCommandExecutor {
        context(LspHandlerContext, LSServer)
        override suspend fun executeForDocument(
            documentUri: DocumentUri,
            otherArgs: List<JsonElement>,
        ): List<TextEdit> {
            return withAnalysisContext {
                val file = documentUri.findVirtualFile() ?: return@withAnalysisContext emptyList()
                val argument = otherArgs.firstOrNull()?.let { LSP.json.decodeFromJsonElement(dataSerializer, it) } ?: (NoData as P)
                execute(file, argument)
            }
        }
    }
}