// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.extract

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import com.intellij.psi.util.startOffset
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findPsiFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LspCommand
import com.jetbrains.ls.api.features.impl.common.extract.ExtractActionKind
import com.jetbrains.ls.api.features.impl.common.utils.LSRefactoringMemberProviderBase
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.introduceVariable.K2IntroduceVariableHandler
import org.jetbrains.kotlin.idea.refactoring.findElementAtRange
import org.jetbrains.kotlin.idea.refactoring.getSmartSelectSuggestions
import org.jetbrains.kotlin.idea.refactoring.introduce.IntroduceRefactoringException
import org.jetbrains.kotlin.idea.refactoring.introduce.KotlinIntroduceVariableHelper
import org.jetbrains.kotlin.idea.refactoring.introduce.KotlinIntroduceVariableService
import org.jetbrains.kotlin.idea.refactoring.introduce.extractableSubstringInfo
import org.jetbrains.kotlin.idea.refactoring.introduce.substringContextOrThis
import org.jetbrains.kotlin.idea.util.ElementKind
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBackingField
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtConstructorDelegationReferenceExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStatementExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Provides the "Extract to local variable" refactoring for Kotlin.
 * It drives [K2IntroduceVariableHandler] without an editor, so the innermost local container is used.
 * A class-body or file container is skipped: an extraction there introduces a property.
 */
internal object LSKotlinExtractVariableProvider :
    LSRefactoringMemberProviderBase<LSKotlinExtractVariableProvider.ExtractVariableContext?>() {

    override val commandName: String = "refactor.extract.variable"
    override val descriptorTitle: @LspCommand String = LspServerBundle.message("command.extract.to.local.variable")
    override val actionKind: CodeActionKind = ExtractActionKind.RefactorExtractVariable

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    private val LOG = Logger.getInstance(LSKotlinExtractVariableProvider::class.java)

    context(analysisContext: LSAnalysisContext)
    override fun getChoices(file: VirtualFile, selectedRange: TextRange): ChoicesResult? {
        val psiFile = file.findPsiFile() as? KtFile ?: return null
        val expression = findExtractableExpression(psiFile, selectedRange) ?: return null
        val service = psiFile.project.service<KotlinIntroduceVariableService>()
        if (expression.extractableSubstringInfo == null && service.hasUnitType(expression)) return null
        val containers = service.localVariableContainers(expression)
        if (containers.isEmpty()) return null
        val occurrenceCount = service.findOccurrences(expression, containers.first().occurrenceContainer).size
        val choices = when {
            occurrenceCount > 1 -> listOf(
                descriptorTitle,
                LspServerBundle.message("command.extract.to.local.variable.all.occurrences", occurrenceCount),
            )
            else -> listOf(descriptorTitle)
        }
        val selection = expression.extractableSubstringInfo?.contentRange ?: expression.textRange
        return ChoicesResult.Choices(choices, selection)
    }

    context(analysisContext: LSAnalysisContext)
    override fun getWriteContext(file: VirtualFile, selection: TextRange, choice: String): ExtractVariableContext? {
        val psiFile = file.findPsiFile() as? KtFile ?: return null
        return ExtractVariableContext(psiFile, selection, replaceAllOccurrences = choice != descriptorTitle)
    }

    context(server: LSServer, analysisContext: LSAnalysisContext, handlerContext: LspHandlerContext)
    override suspend fun executeRefactoring(context: ExtractVariableContext?): RefactoringResult? {
        if (context == null) return null
        val fileDocument = context.file.fileDocument
        val oldText = readAction { fileDocument.text }

        val introduced = withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                extractVariable(context)?.let { SmartPointerManager.createPointer(it) }
            }
        } ?: return null

        return readAction {
            val uri = context.file.virtualFile.uri
            val navigationRange = introduced.element?.let { TextRange(it.startOffset, it.startOffset).toLspRange(fileDocument) }
            RefactoringResult(
                listOf(
                    TextDocumentEdit(
                        textDocument = TextDocumentIdentifier(uri = DocumentUri(uri), version = server.documents.getVersion(DocumentUri(uri).uri) ?: 0),
                        edits = computeTextEdits(oldText, fileDocument.text, granularity = DiffGranularity.WORD),
                    )
                ),
                navigationRange,
                startRename = true,
            )
        }
    }

    /** Returns the element for the caret, or null when the refactoring did not run. */
    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    private fun extractVariable(context: ExtractVariableContext): PsiElement? = allowAnalysisOnEdt {
        allowAnalysisFromWriteAction {
            try {
                doExtractVariable(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IntroduceRefactoringException) {
                null
            } catch (_: CommonRefactoringUtil.RefactoringErrorHintException) {
                null
            } catch (e: Exception) {
                LOG.error("Unexpected exception during extract variable", e)
                null
            }
        }
    }

    private fun doExtractVariable(context: ExtractVariableContext): PsiElement? {
        val expression = findExtractableExpression(context.file, context.selection) ?: return null
        val service = context.file.project.service<KotlinIntroduceVariableService>()
        if (expression.extractableSubstringInfo == null && service.hasUnitType(expression)) return null
        val container = service.localVariableContainers(expression).firstOrNull() ?: return null
        val occurrences = when {
            context.replaceAllOccurrences -> service.findOccurrences(expression, container.occurrenceContainer)
            else -> listOf(expression)
        }
        var introduced: KtDeclaration? = null
        K2IntroduceVariableHandler.collectCandidateTargetContainersAndDoRefactoring(
            project = context.file.project,
            editor = null,
            expressionToExtract = expression,
            isVar = false,
            occurrencesToReplace = occurrences,
            targetContainer = container.targetContainer,
            onNonInteractiveFinish = { introduced = it },
        )
        val declaration = introduced ?: return null
        return (declaration as? KtProperty)?.nameIdentifier ?: declaration
    }

    // a class-body or file container introduces a property, not a local variable
    private fun KotlinIntroduceVariableService.localVariableContainers(expression: KtExpression): List<KotlinIntroduceVariableHelper.Containers> =
        getContainersForExpression(expression).filterNot { it.targetContainer is KtClassBody || it.targetContainer is KtFile }

    private fun findExtractableExpression(file: KtFile, selectedRange: TextRange): KtExpression? {
        val found = try {
            when {
                selectedRange.isEmpty -> getSmartSelectSuggestions(file, selectedRange.startOffset, ElementKind.EXPRESSION).firstOrNull()
                selectedRange.endOffset <= file.textLength ->
                    findElementAtRange(file, selectedRange.startOffset, selectedRange.endOffset, listOf(ElementKind.EXPRESSION), failOnEmptySuggestion = false)
                else -> null
            }
        } catch (_: IntroduceRefactoringException) {
            null
        }
        val candidate = found as? KtExpression ?: return null
        val expression = KtPsiUtil.safeDeparenthesize(candidate)
        if (expression.isAssignmentLHS()) return null
        if (!isExtractableByPsi(expression)) return null
        return expression
    }

    // mirrors org.jetbrains.kotlin.idea.base.psi.isAssignmentLHS, which is not on this module's classpath
    private fun KtExpression.isAssignmentLHS(): Boolean = parents(withSelf = false).any {
        KtPsiUtil.isAssignment(it) && (it as KtBinaryExpression).left == this
    }

    // mirrors the protected KotlinIntroduceVariableHandler.isRefactoringApplicableByPsi, without the error hints
    private fun isExtractableByPsi(expression: KtExpression): Boolean {
        val physicalExpression = expression.substringContextOrThis
        val isApplicable = when (val parent = physicalExpression.parent) {
            is KtProperty -> expression !is KtBackingField
            is KtQualifiedExpression -> parent.receiverExpression == physicalExpression
            is KtOperationExpression if parent.operationReference == physicalExpression -> false
            else -> physicalExpression !is KtStatementExpression
        }
        if (!isApplicable) return false

        return PsiTreeUtil.getNonStrictParentOfType(
            physicalExpression,
            KtTypeReference::class.java,
            KtConstructorCalleeExpression::class.java,
            KtSuperExpression::class.java,
            KtConstructorDelegationReferenceExpression::class.java,
            KtAnnotationEntry::class.java,
        ) == null
    }

    internal data class ExtractVariableContext(
        val file: KtFile,
        val selection: TextRange,
        val replaceAllOccurrences: Boolean,
    )
}
