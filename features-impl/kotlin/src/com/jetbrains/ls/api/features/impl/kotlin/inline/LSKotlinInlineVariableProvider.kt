// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.inline

import com.intellij.modcommand.ModDisplayMessage
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findPsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.LSCommandExecutor
import com.jetbrains.ls.api.features.impl.common.inline.InlineActionKind
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findElementUnderCaret
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.searching.usages.ReferencesSearchScopeHelper
import org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlinePropertyProcessor
import org.jetbrains.kotlin.idea.refactoring.deleteWithCompanion
import org.jetbrains.kotlin.idea.refactoring.inline.AbstractKotlinInlinePropertyProcessor
import org.jetbrains.kotlin.idea.refactoring.inline.codeInliner.replaceUsages
import org.jetbrains.kotlin.idea.refactoring.inline.findCallableConflictForUsage
import org.jetbrains.kotlin.idea.references.readWriteAccess
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelectorOrThis
import org.jetbrains.kotlin.resolve.references.ReferenceAccess

internal object LSKotlinInlineVariableProvider : LSCodeActionProvider, LSCommandDescriptorProvider {
    private const val COMMAND_NAME = "kotlin.inline.variable"

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(InlineActionKind.RefactorInlineVariable)

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val action = server.withAnalysisContextAndFileSettings(documentUri.uri) {
            readAction {
                val property = findProperty(documentUri, params.range) ?: return@readAction null
                when (AbstractKotlinInlinePropertyProcessor.extractInitialization(property).initializerOrNull) {
                    null -> errorAction(cannotInlineMessage(property))
                    else -> inlineAction(documentUri, params.range, server.documents.getVersion(documentUri.uri) ?: 0)
                }
            }
        } ?: return@flow
        emit(action)
    }

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = title(),
                name = COMMAND_NAME,
                executor = object : LSCommandExecutor {
                    context(server: LSServer, handlerContext: LspHandlerContext)
                    override suspend fun execute(arguments: List<JsonElement>): JsonElement {
                        require(arguments.size == 3) { "Expected 3 arguments, got: ${arguments.size}" }
                        val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments[0])
                        val range = LSP.json.decodeFromJsonElement<Range>(arguments[1])
                        val listedVersion = LSP.json.decodeFromJsonElement<Int>(arguments[2])
                        val changes = server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
                            if ((server.documents.getVersion(documentUri.uri) ?: 0) != listedVersion) failStaleDocument()
                            when (val prepared = readAction { prepare(documentUri, range) }) {
                                null -> failInline(LspServerBundle.message("error.performing.refactoring"))
                                is Prepared.CannotInline -> failInline(prepared.message)
                                is Prepared.Ready -> doRefactoring(
                                    processor = prepared.processor,
                                    granularity = DiffGranularity.WORD,
                                    uriToSkip = null,
                                    showNotificationWithError = true,
                                )
                            }
                        }
                        val edits = changes.filterIsInstance<TextDocumentEdit>().filter { it.edits.isNotEmpty() }
                        if (edits.isEmpty()) return JsonPrimitive(true)
                        lspClient.request(
                            ApplyEditRequests.ApplyEdit,
                            ApplyWorkspaceEditParams(
                                label = title(),
                                edit = WorkspaceEdit(documentChanges = edits),
                            ),
                        )
                        return JsonPrimitive(true)
                    }
                },
            ),
        )

    private fun inlineAction(documentUri: DocumentUri, range: Range, version: Int): CodeAction {
        val title = title()
        return CodeAction(
            title = title,
            kind = InlineActionKind.RefactorInlineVariable,
            command = Command(
                title = title,
                command = COMMAND_NAME,
                arguments = listOf(
                    LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                    LSP.json.encodeToJsonElement<Range>(range),
                    JsonPrimitive(version),
                ),
            ),
        )
    }

    /** Reports command arguments that name a property that is gone or is not inlinable anymore. */
    private fun failInline(message: @Nls String): Nothing =
        throwLspError(ExecuteCommand, message, Unit, ErrorCodes.InvalidParams, null)

    /** Reports a document that changed after the action was listed: the range may point at another declaration now. */
    private fun failStaleDocument(): Nothing =
        throwLspError(ExecuteCommand, LspServerBundle.message("error.action.not.available"), Unit, ErrorCodes.ContentModified, null)

    context(analysisContext: LSAnalysisContext)
    private fun prepare(documentUri: DocumentUri, range: Range): Prepared? {
        val property = findProperty(documentUri, range) ?: return null
        val initializer = AbstractKotlinInlinePropertyProcessor.extractInitialization(property).initializerOrNull
            ?: return Prepared.CannotInline(cannotInlineMessage(property))
        val isWhenSubjectVariable = (property.parent as? KtWhenExpression)?.subjectVariable == property
        val processor = KotlinInlinePropertyProcessor(
            declaration = property,
            reference = null,
            inlineThisOnly = false,
            deleteAfter = true,
            isWhenSubjectVariable = isWhenSubjectVariable,
            editor = null,
            statementToDelete = initializer.assignment,
            project = analysisContext.project,
        )
        return Prepared.Ready(LSKotlinInlineVariableProcessor(property, processor, deleteDeclaration = !isWhenSubjectVariable))
    }

    /**
     * @see BaseRefactoringAction.getElementAtCaret
     */
    context(analysisContext: LSAnalysisContext)
    private fun findProperty(documentUri: DocumentUri, range: Range): KtProperty? {
        val virtualFile = documentUri.findVirtualFile() ?: return null
        val document = virtualFile.findDocument() ?: return null
        val file = virtualFile.findPsiFile() ?: return null
        val textRange = range.toTextRange(document)
        val editor = ImaginaryEditor(analysisContext.project, file.fileDocument)
        val candidate = findElementUnderCaret(editor, textRange.endOffset) ?: BaseRefactoringAction.getElementAtCaret(editor, file)
        val property = candidate as? KtProperty ?: return null
        if (!property.isLocal || property.name == null) return null
        return property
    }

    private fun cannotInlineMessage(property: KtProperty): @Nls String {
        val name = property.name.toString()
        val reason = when {
            hasWriteUsages(property) -> RefactoringBundle.message("variable.has.no.dominating.definition", name)
            else -> RefactoringBundle.message("variable.has.no.initializer", name)
        }
        return RefactoringBundle.getCannotRefactorMessage(reason)
    }

    /**
     * Repeats the write-usage scan of [AbstractKotlinInlinePropertyProcessor.extractInitialization],
     * which does not expose its error message.
     */
    private fun hasWriteUsages(property: KtProperty): Boolean {
        if (property.initializer != null) return true
        val definitionScope = property.parent ?: return false
        return ReferencesSearch.search(property, LocalSearchScope(definitionScope)).anyMatch { reference ->
            val expression = (reference.element as? KtExpression)?.getQualifiedExpressionForSelectorOrThis()
            expression != null && expression.readWriteAccess(useResolveForReadWrite = true) != ReferenceAccess.READ
        }
    }

    private fun errorAction(message: @Nls String): CodeAction = applyFixCodeAction(
        title = title(),
        kind = InlineActionKind.RefactorInlineVariable,
        modCommandData = ModCommandData.DisplayMessage(message, ModDisplayMessage.MessageKind.ERROR),
    )

    private fun title(): @Nls String = LspServerBundle.message("command.inline.local.variable")

    private sealed interface Prepared {
        data class CannotInline(val message: @Nls String) : Prepared
        data class Ready(val processor: LSKotlinInlineVariableProcessor) : Prepared
    }
}

/**
 * Drives [KotlinInlinePropertyProcessor] headlessly: it inlines every usage and deletes the declaration.
 *
 * The refactoring steps mirror
 * [AbstractKotlinInlineNamedDeclarationProcessor][org.jetbrains.kotlin.idea.refactoring.inline.AbstractKotlinInlineNamedDeclarationProcessor]
 * for a local variable, so the override and the cross-language branches are not needed.
 */
private class LSKotlinInlineVariableProcessor(
    private val declaration: KtProperty,
    private val processor: KotlinInlinePropertyProcessor,
    private val deleteDeclaration: Boolean,
) : LSRefactoringProcessor {
    override fun findUsages(): Array<UsageInfo> =
        ReferencesSearchScopeHelper.search(declaration).findAll().map { UsageInfo(it) }.toTypedArray()

    override fun collectConflicts(refUsages: Ref<Array<UsageInfo>>, conflicts: MultiMap<PsiElement, String>) {
        val usages = refUsages.get()
        processor.additionalPreprocessUsages(usages, conflicts)
        usages.forEach { usage ->
            val element = usage.element ?: return@forEach
            findCallableConflictForUsage(element)?.let { conflicts.putValue(element, it) }
        }
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> = initialUsages

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> =
        usages.mapNotNull { it.file } + listOfNotNull(declaration.containingFile)

    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    override fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction) {
        allowAnalysisOnEdt {
            allowAnalysisFromWriteAction {
                doPerformRefactoring(usages)
            }
        }
    }

    private fun doPerformRefactoring(usages: Array<UsageInfo>) {
        if (usages.isNotEmpty()) {
            val replacementStrategy = processor.createReplacementStrategy() ?: return
            val references = usages.mapNotNull { it.element as? KtReferenceExpression }
            replacementStrategy.replaceUsages(
                usages = if (LSKotlinInlineVariableTestHooks.skipFirstUsage) references.drop(1) else references,
                unwrapSpecialUsages = true,
                unwrapSpecialUsageOrNull = processor::unwrapSpecialUsage,
            )
        }
        if (declaration.isWritable) {
            checkAllUsagesReplaced()
            if (deleteDeclaration) {
                declaration.deleteWithCompanion()
                processor.postDeleteAction()
            }
        }
        processor.postAction()
    }

    /**
     * [replaceUsages] logs and skips a failed usage; a removal of the declaration then breaks the code.
     * The declaration goes away on both paths: the deletion here, and the `when`-subject
     * replacement in [AbstractKotlinInlinePropertyProcessor.postAction].
     * A write usage is not an inline failure: the removal deletes it together with the declaration.
     */
    private fun checkAllUsagesReplaced() {
        val hasRemainingReadUsages = ReferencesSearchScopeHelper.search(declaration).anyMatch { reference ->
            val expression = (reference.element as? KtExpression)?.getQualifiedExpressionForSelectorOrThis()
            expression != null && expression.readWriteAccess(useResolveForReadWrite = true) == ReferenceAccess.READ
        }
        check(!hasRemainingReadUsages) {
            LspServerBundle.message("error.inline.usages.not.replaced", declaration.name.toString())
        }
    }

    override fun createEventData(): RefactoringEventData = RefactoringEventData().apply { addElement(declaration) }
}

/**
 * No plain Kotlin code makes [replaceUsages] fail deterministically.
 * The hook simulates the failure: it skips the first usage the way [replaceUsages] skips a failed one.
 */
@ApiStatus.Internal
object LSKotlinInlineVariableTestHooks {
    @Volatile
    internal var skipFirstUsage: Boolean = false

    @TestOnly
    fun <T> withFailedUsageReplacement(body: () -> T): T {
        skipFirstUsage = true
        try {
            return body()
        } finally {
            skipFirstUsage = false
        }
    }
}
