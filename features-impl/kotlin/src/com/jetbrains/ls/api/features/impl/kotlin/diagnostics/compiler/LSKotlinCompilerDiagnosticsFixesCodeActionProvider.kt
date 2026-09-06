// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler

import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringHash
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticWithData
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.impl.common.modcommands.LazyFix
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.toLazyFix
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixService
import org.jetbrains.kotlin.psi.KtFile

private val LOG = logger<LSKotlinCompilerDiagnosticsFixesCodeActionProvider>()

/**
 * Offers the quick fixes for the Kotlin compiler diagnostics of a `codeAction` request.
 *
 * The listing runs in the shared analysis context and stays cheap: it computes the fix names only.
 * A client that declares `lazyIntentions` gets one command per fix. The command carries the document uri,
 * the diagnostic identity, the fix index and name, and a hash of the document text. A closed document has
 * no version to compare, so the hash is the staleness token. The edits are computed in
 * `workspace/executeCommand` when the user picks the fix. The command re-computes the fix list in a write
 * analysis context and answers [ErrorCodes.ContentModified] when the document text or the fix list changed.
 * A client without `lazyIntentions` keeps the eager listing, because a choice tree can only be flattened
 * into separate fixes by performing it.
 */
internal object LSKotlinCompilerDiagnosticsFixesCodeActionProvider : LSCodeActionProvider, LSCommandDescriptorProvider {
    private const val COMMAND_NAME = "kotlin.applyCompilerDiagnosticFix"

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val diagnosticData = params.diagnosticData<KotlinCompilerDiagnosticData>().ifEmpty { return@flow }
        val documentUri = params.textDocument.uri
        server.withAnalysisContextAndFileSettings(documentUri.uri) {
            readAction {
                listActions(documentUri, diagnosticData)
            }
        }.forEach { emit(it) }
    }

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = LspServerBundle.message("command.apply.kotlin.quick.fix"),
                name = COMMAND_NAME,
                executor = { arguments ->
                    require(arguments.size == 5) { "Expected 5 arguments, got: ${arguments.size}" }
                    val server = contextOf<LSServer>()
                    val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments[0])
                    val diagnosticData = LSP.json.decodeFromJsonElement<KotlinCompilerDiagnosticData>(arguments[1])
                    val index = LSP.json.decodeFromJsonElement<Int>(arguments[2])
                    val name = LSP.json.decodeFromJsonElement<String>(arguments[3])
                    val listedContentHash = LSP.json.decodeFromJsonElement<String>(arguments[4]).toLong()
                    val data = server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
                        readAction {
                            val fix = findFix(documentUri, diagnosticData, index, name, listedContentHash) ?: failStaleDocument()
                            val performed = fix.perform() ?: failStaleDocument()
                            ModCommandData.from(performed.command, performed.context, server) ?: failFixNotSupported()
                        }
                    }
                    server.withAnalysisContextAndFileSettings(documentUri.uri) {
                        executeCommand(data, lspClient)
                    }
                    JsonPrimitive(true)
                },
            ),
        )

    context(server: LSServer, analysisContext: LSAnalysisContext)
    private fun listActions(
        documentUri: DocumentUri,
        diagnosticData: List<DiagnosticWithData<KotlinCompilerDiagnosticData>>,
    ): List<CodeAction> {
        val virtualFile = documentUri.findVirtualFile() ?: return emptyList()
        val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return emptyList()
        val document = virtualFile.findDocument() ?: return emptyList()
        val contentHash = contentHash(document)
        return analyze(ktFile) {
            val kaDiagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
            if (kaDiagnostics.isEmpty()) return@analyze emptyList()
            val editor = ImaginaryEditor(project, document).apply {
                caretModel.primaryCaret.moveToOffset(0)
            }
            val result = mutableListOf<CodeAction>()
            for (data in diagnosticData) {
                val kaDiagnostic = kaDiagnostics.firstOrNull { data.data.matches(it) } ?: continue
                val fixes = quickFixesFor(ktFile, editor, kaDiagnostic)
                result += when {
                    server.config.clientSupportsLazyIntentions -> fixes.mapIndexed { index, fix ->
                        deferredFixAction(documentUri, data, index, fix.name, contentHash)
                    }
                    // Flattening a choice tree performs every fix, so this client keeps the eager listing.
                    else -> fixes.flatMap { it.toModCommandFixes() }
                        .map { fix -> applyFixCodeAction(fix.name, CodeActionKind.QuickFix, fix.data, data.diagnostic) }
                }
            }
            result
        }
    }

    /** The fix at [index] of the re-computed fix list, or `null` when the document text or the fix list does not match the listing anymore. */
    context(_: LSAnalysisContext)
    private fun findFix(
        documentUri: DocumentUri,
        diagnosticData: KotlinCompilerDiagnosticData,
        index: Int,
        name: String,
        listedContentHash: Long,
    ): LazyFix? {
        val virtualFile = documentUri.findVirtualFile() ?: return null
        val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return null
        val document = virtualFile.findDocument() ?: return null
        if (contentHash(document) != listedContentHash) return null
        return analyze(ktFile) {
            val kaDiagnostic = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                .firstOrNull { diagnosticData.matches(it) } ?: return@analyze null
            val editor = ImaginaryEditor(project, document).apply {
                caretModel.primaryCaret.moveToOffset(0)
            }
            quickFixesFor(ktFile, editor, kaDiagnostic).getOrNull(index)?.takeIf { it.name == name }
        }
    }

    context(kaSession: KaSession)
    private fun quickFixesFor(
        file: KtFile,
        editor: Editor,
        kaDiagnostic: KaDiagnosticWithPsi<*>,
    ): List<LazyFix> {
        val actionContext = ActionContext.from(editor, file)
        val quickFixService = KotlinQuickFixService.getInstance()
        return with(quickFixService) { getQuickFixesWithCatchingFor(kaDiagnostic) + getLazyQuickFixesWithCatchingFor(kaDiagnostic) }
            .mapNotNull { fix ->
                fix.getOrHandleException { LOG.warn(it) }
            }
            .mapNotNull { intentionAction ->
                val modCommandAction = intentionAction.asModCommandAction()
                if (modCommandAction == null) {
                    LOG.warn("Cannot convert $intentionAction to ModCommandAction")
                }
                modCommandAction
            }
            .mapNotNull { it.toLazyFix(actionContext) }
            .toList()
    }

    private fun deferredFixAction(
        documentUri: DocumentUri,
        diagnostic: DiagnosticWithData<KotlinCompilerDiagnosticData>,
        index: Int,
        name: @NlsSafe String,
        contentHash: Long,
    ): CodeAction = CodeAction(
        title = name,
        kind = CodeActionKind.QuickFix,
        diagnostics = listOf(diagnostic.diagnostic),
        command = Command(
            title = name,
            command = COMMAND_NAME,
            arguments = listOf(
                LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                LSP.json.encodeToJsonElement(diagnostic.data),
                JsonPrimitive(index),
                JsonPrimitive(name),
                // A string, because a JSON number round-trips through a JavaScript client with only 53 bits.
                JsonPrimitive(contentHash.toString()),
            ),
        ),
    )

    /** The staleness token of the listing: a closed document has no version, so the text itself is hashed. */
    private fun contentHash(document: Document): Long = StringHash.buz(document.immutableCharSequence)

    /** Reports a document that changed after the listing: the same fix may not exist anymore. */
    private fun failStaleDocument(): Nothing =
        throwLspError(ExecuteCommand, LspServerBundle.message("error.action.not.available"), Unit, ErrorCodes.ContentModified, null)

    /** Reports a fix whose command has no LSP representation for this client. */
    private fun failFixNotSupported(): Nothing =
        throwLspError(ExecuteCommand, LspServerBundle.message("error.fix.not.supported"), Unit, ErrorCodes.InvalidParams, null)
}
