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
import com.jetbrains.ls.api.features.impl.common.inline.InlineActionKind
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findElementUnderCaret
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.annotations.Nls
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

internal object LSKotlinInlineVariableProvider : LSCodeActionProvider {
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(InlineActionKind.RefactorInlineVariable)

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val propertyFound = server.withAnalysisContextAndFileSettings(documentUri.uri) {
            readAction { findProperty(documentUri, params.range) != null }
        }
        if (!propertyFound) return@flow
        val action = server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
            when (val prepared = readAction { prepare(documentUri, params.range) }) {
                null -> null
                is Prepared.CannotInline -> errorAction(prepared.message)
                is Prepared.Ready -> inlineAction(prepared.processor)
            }
        } ?: return@flow
        emit(action)
    }

    context(server: LSServer, _: LSAnalysisContext, _: LspHandlerContext)
    private suspend fun inlineAction(processor: LSKotlinInlineVariableProcessor): CodeAction? {
        val changes = try {
            doRefactoring(processor, DiffGranularity.WORD, uriToSkip = null, showNotificationWithError = false)
        } catch (e: LspException) {
            return errorAction(e.message ?: LspServerBundle.message("error.performing.refactoring"))
        }
        val edits = changes.filterIsInstance<TextDocumentEdit>().filter { it.edits.isNotEmpty() }
        if (edits.isEmpty()) return null
        return CodeAction(
            title = title(),
            kind = InlineActionKind.RefactorInlineVariable,
            edit = WorkspaceEdit(documentChanges = edits),
        )
    }

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
            replacementStrategy.replaceUsages(
                usages = usages.mapNotNull { it.element as? KtReferenceExpression },
                unwrapSpecialUsages = true,
                unwrapSpecialUsageOrNull = processor::unwrapSpecialUsage,
            )
        }
        if (deleteDeclaration && declaration.isWritable) {
            declaration.deleteWithCompanion()
            processor.postDeleteAction()
        }
        processor.postAction()
    }

    override fun createEventData(): RefactoringEventData = RefactoringEventData().apply { addElement(declaration) }
}
