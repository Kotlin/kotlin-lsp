// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.model.psi.PsiSymbolService
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.jetbrains.ls.api.core.*
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.rename.LSRenameProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.*
import java.util.concurrent.atomic.AtomicReference

class LSRenameProviderCommonImpl(
    override val supportedLanguages: Set<LSLanguage>,
) : LSRenameProvider {
    companion object {
        init {
            // Suppress irrelevant "Can't invokeAndWait from WT to EDT: probably leads to deadlock" messages
            ApplicationUtil.LOG.setLevel(LogLevel.OFF)
        }

        private val LOG = logger<LSRenameProviderCommonImpl>()
    }

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun rename(params: RenameParams): WorkspaceEdit? {
        val originals = mutableMapOf<PsiFile, String>()
        val renames = mutableListOf<RenameFile>()
        val edits: List<TextDocumentEdit> = withWriteAnalysisContext {
            val processor = runReadAction a@{
                val file = params.findVirtualFile() ?: return@a null
                val psiFile = file.findPsiFile(project) ?: return@a null
                val document = file.findDocument() ?: return@a null
                val offset = document.offsetByPosition(params.position)
                val psiSymbolService = PsiSymbolService.getInstance()
                val targets = targetSymbols(psiFile, offset).mapNotNull { psiSymbolService.extractElementFromSymbol(it) }
                val element = targets.firstOrNull()
                    ?: throwLspError(RenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams, null)
                element.containingFile?.let {
                    originals[it] = it.text
                }
                Renamer(project, element, params.newName, true, false)
            } ?: return@withWriteAnalysisContext emptyList()

            runReadAction { processor.foundUsages }
                .map { it.file }
                .distinct()
                .filterNotNull()
                .forEach { originals[it] = it.text }

            val err = AtomicReference<Throwable?>(null)
            invokeAndWaitIfNeeded {
                CommandProcessor.getInstance().executeCommand(project, {
                    try {
                        runBlockingCancellable {
                            withRenamesEnabled {
                                writeIntentReadAction {
                                    processor.rename()
                                }
                            }
                                .forEach { (oldUri, newUri) ->
                                    renames.add(RenameFile(DocumentUri(oldUri), DocumentUri(newUri)))
                                }
                        }
                    } catch (e: Throwable) {
                        err.set(e)
                    }
                }, "Rename", null)
            }

            err.get()?.let {
                LOG.warn("Error renaming element", it)
                when (it) {
                    is LspException -> throw it
                    else -> {
                        val cause = generateSequence(it) { e -> e.cause?.takeIf { c -> c != e } }
                            .filterIsInstance<IncorrectOperationException>()
                            .firstOrNull() ?: it

                        throwLspError(RenameRequestType, cause.message ?: "Error renaming element", Unit, ErrorCodes.InvalidParams, cause)
                    }
                }
            }

            runReadAction {
                originals.map { (file, original) ->
                    val uri = DocumentUri(file.virtualFile.uri)
                    val version = documents.getVersion(uri.uri)
                        ?: 0 // According to LSP spec, it should be null, but our serialization would drop it, causing an error on the LSP side. Zero seems to work.
                    val id = TextDocumentIdentifier(uri, version)
                    val edits = computeTextEdits(original, file.text)
                    TextDocumentEdit(id, edits)
                }
            }
        }

        return WorkspaceEdit(documentChanges = edits + renames)
    }
}