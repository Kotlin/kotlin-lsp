// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.model.psi.PsiSymbolService
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.jetbrains.ls.api.core.*
import com.jetbrains.ls.api.core.util.*
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.rename.LSRenameProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.*

abstract class LSRenameProviderBase(
    override val supportedLanguages: Set<LSLanguage>,
) : LSRenameProvider {
    companion object {
        init {
            // Suppress irrelevant "Can't invokeAndWait from WT to EDT: probably leads to deadlock" messages
            ApplicationUtil.LOG.setLevel(LogLevel.OFF)
        }

        private val LOG = logger<LSRenameProviderBase>()
    }

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun rename(params: RenameParams): WorkspaceEdit {
        val changes: List<FileChange> = withWriteAnalysisContext {
            val context = readAction {
                val file = params.findVirtualFile() ?: return@readAction null
                val document = file.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val psiFile = file.findPsiFile(project) ?: return@readAction null
                val psiSymbolService = PsiSymbolService.getInstance()
                val targets = targetSymbols(psiFile, offset).mapNotNull { psiSymbolService.extractElementFromSymbol(it) }
                val target = targets.firstOrNull()
                    ?: throwLspError(RenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams, null)

                Context(target, params.newName, DiffGranularity.CHARACTER)
            } ?: return@withWriteAnalysisContext emptyList()

            doRename(context) ?: emptyList()
        }

        return WorkspaceEdit(documentChanges = changes)
    }

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun renameFile(params: FileRename): WorkspaceEdit? {
        val edits = withWriteAnalysisContext {
            val context = readAction {
                // check that a file was already renamed on the previous step
                if (params.newUri.findVirtualFile() != null) return@readAction null

                val nameChange = computeNameChange(params.oldUri, params.newUri) ?: return@readAction null
                val file = params.oldUri.findVirtualFile() ?: return@readAction null
                val psiFile = file.findPsiFile(project) ?: return@readAction null
                val target = getTargetClass(psiFile, nameChange.oldName) ?: return@readAction null
                Context(target, nameChange.newName, DiffGranularity.WORD, params.oldUri)
            } ?: return@withWriteAnalysisContext null

            doRename(context)
        }

        return WorkspaceEdit(documentChanges = edits)
    }

    context(server: LSServer)
    private suspend fun doRename(context: Context): List<FileChange>? {
        val originals = mutableMapOf<PsiFile, String>()
        val renames = mutableListOf<RenameFile>()
        val target = context.target
        val newName = context.newName
        val processor = readAction {
            if (!target.isValid) return@readAction null
            target.containingFile?.let {
                originals[it] = it.text
            }

            val processor = Renamer(target.project, target, newName, true, false)
            processor.usages.map { it.file }.distinct().filterNotNull().forEach { originals[it] = it.text}
            processor
        } ?: return null

        try {
            renameAndGetChangedFiles(processor).mapNotNullTo(renames) { (oldUri, newUri) ->
                if (oldUri == context.uriToSkip) return@mapNotNullTo null
                RenameFile(DocumentUri(oldUri), DocumentUri(newUri))
            }
        } catch (ex: Throwable) {
            LOG.warn("Error renaming element", ex)
            when (ex) {
                is LspException -> throw ex
                else -> {
                    val cause = generateSequence(ex) { it.cause?.takeIf { c -> c != it } }
                        .filterIsInstance<IncorrectOperationException>()
                        .firstOrNull() ?: ex

                    throwLspError(
                        RenameRequestType,
                        cause.message ?: "Error renaming element",
                        Unit,
                        ErrorCodes.InvalidParams,
                        cause
                    )
                }
            }
        }

        val edits = readAction {
            originals.map { (file, original) ->
                val uri = DocumentUri(file.virtualFile.uri)
                val version = server.documents.getVersion(uri.uri)
                    ?: 0 // According to LSP spec, it should be null, but our serialization would drop it, causing an error on the LSP side. Zero seems to work.
                val id = TextDocumentIdentifier(uri, version)
                val edits = computeTextEdits(original, file.text, context.granularity)
                TextDocumentEdit(id, edits)
            }
        }
        return edits + renames
    }

    context(_: LSServer)
    private fun renameAndGetChangedFiles(processor: Renamer): Map<URI, URI> {
        // FIXME(Georgii Ustinov): LSP-342
        return invokeAndWaitIfNeeded {
            runBlockingCancellable {
                withRenamesEnabled {
                    writeIntentReadAction {
                        processor.rename()
                    }
                }
            }
        }
    }

    protected abstract fun getTargetClass(psiFile: PsiFile, name: String): PsiElement?

    private fun computeNameChange(old: URI, new: URI): NameChange? {
        val newExtension = new.fileExtension
        val oldExtension = old.fileExtension
        if (oldExtension == null || newExtension == null || newExtension != oldExtension) return null
        if (old.scheme != new.scheme) return null

        val oldName = old.fileName
        val newName = new.fileName
        if (oldName == newName) return null
        return NameChange(
            oldName.getPureName(oldExtension),
            newName.getPureName(newExtension)
        )
    }

    private fun String.getPureName(extension: String) = removeSuffix(extension).trimEnd { it == '.' }

    private class NameChange(
        val oldName: String,
        val newName: String
    )

    private class Context(
        val target: PsiElement,
        val newName: String,
        val granularity: DiffGranularity,
        val uriToSkip : URI? = null
    )
}