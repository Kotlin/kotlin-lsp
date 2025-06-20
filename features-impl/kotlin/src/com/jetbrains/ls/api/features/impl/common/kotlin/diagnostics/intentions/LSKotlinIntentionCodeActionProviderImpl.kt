// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.intentions

import com.intellij.codeInsight.template.Expression
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.ModShowConflicts
import com.intellij.modcommand.ModTemplateBuilder
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.descendantsOfType
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.core.withWritableFile
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.document.LSDocumentCommandExecutor
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.getElementContext
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinApplicableModCommandAction
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.MovePropertyToConstructorIntention
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.util.function.Function

internal object LSKotlinIntentionCodeActionProviderImpl : LSCodeActionProvider, LSCommandDescriptorProvider {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    private fun createActions(): List<KotlinApplicableModCommandAction<*, *>> {
        return listOf(
            MovePropertyToConstructorIntention(),
        )
    }


    context(_: LSServer)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val uri = params.textDocument.uri.uri
        withAnalysisContext {
            runReadAction {
                val file = uri.findVirtualFile() ?: return@runReadAction emptyList()
                val ktFile = file.findPsiFile(project) as? KtFile ?: return@runReadAction emptyList()
                val document = file.findDocument() ?: return@runReadAction emptyList()
                val actions = createActions()
                analyze(ktFile) {
                    val result = mutableListOf<CodeAction>()
                    for (ktElement in ktFile.descendantsOfType<KtElement>()) {
                        if (!params.range.intersects(ktElement.textRange.toLspRange(document))) continue
                        val actionContext = createActionContext(ktFile, ktElement)
                        for (action in actions) {
                            val codeAction = runCatching {
                                toCodeAction(action, actionContext, ktElement, document, params, uri, file)
                            }.getOrLogException { LOG.debug(it) } ?: continue
                            result += codeAction
                        }
                    }
                    result
                }
            }
        }.forEach { emit(it) }
    }

    context(_: LSAnalysisContext)
    private fun createActionContext(ktFile: KtFile, element: PsiElement) = ActionContext(
        project,
        ktFile,
        element.startOffset,
        TextRange(element.startOffset, element.startOffset), // empty selection
        element,
    )

    context(kaSession: KaSession, _: LSAnalysisContext, _: LSServer)
    private fun toCodeAction(
        action: KotlinApplicableModCommandAction<*, *>,
        actionContext: ActionContext,
        child: KtElement,
        document: Document,
        params: CodeActionParams,
        uri: URI,
        file: VirtualFile
    ): CodeAction? {
        action as KotlinApplicableModCommandAction<KtElement, *>
        val presentation = action.getPresentation(actionContext) ?: return null
        if (!action.isApplicableByPsi(child)) return null
        if (with(action) { kaSession.prepareContext(child) == null }) return null
        val ranges = action.getApplicableRanges(child).map {
            it.shiftRight(child.startOffset).toLspRange(document)
        }
        if (ranges.none { params.range.intersects(it) }) return null
        return CodeAction(
            title = presentation.name,
            kind = CodeActionKind.QuickFix,
            diagnostics = null,
            command = Command(
                commandDescriptor.title,
                commandDescriptor.name,
                arguments = listOf(
                    LSP.json.encodeToJsonElement(uri),
                    LSP.json.encodeToJsonElement(action::class.java.name),
                    LSP.json.encodeToJsonElement(PsiSerializablePointer.create(child, file)),
                ),
            ),
        )
    }

    private val commandDescriptor = LSCommandDescriptor(
        "Kotlin Intention Apply Fix",
        "kotlinIntention.applyFix",
        LSDocumentCommandExecutor e@{ documentUri, otherArgs ->
            val action = otherArgs.getOrNull(0)?.let { actionClassJson ->
                val actionClass = LSP.json.decodeFromJsonElement(String.serializer(), actionClassJson)
                createActions().firstOrNull { it::class.java.name == actionClass }
            } ?: return@e emptyList()
            val psiPointer = otherArgs.getOrNull(1)?.let { psiElementJson ->
                LSP.json.decodeFromJsonElement(PsiSerializablePointer.serializer(), psiElementJson)
            } ?: return@e emptyList()
            action as KotlinApplicableModCommandAction<KtElement, Any>
            withWritableFile(documentUri.uri) {
                withAnalysisContext {
                    val file = runReadAction { documentUri.findVirtualFile() } ?: return@withAnalysisContext emptyList()

                    PsiFileTextEditsCollector.collectTextEdits(file) { ktFile ->
                        check(ktFile is KtFile) { "PsiFile is not KtFile but ${ktFile}" }

                        val psiElement = psiPointer.restore(ktFile) as? KtElement ?: return@collectTextEdits
                        val actionContext = createActionContext(ktFile, psiElement)

                        runCatching {
                            if (action.getPresentation(actionContext) == null) return@collectTextEdits
                            if (!action.isApplicableByPsi(psiElement)) return@collectTextEdits
                            val elementContext = action.getElementContext(psiElement) ?: return@collectTextEdits
                            val psiUpdater = FakeModPsiUpdater(psiElement)
                            action.invoke(actionContext, psiElement, elementContext, psiUpdater)
                        }.getOrLogException {
                            //achtung!
                            LOG.debug(it)
                        }
                    }
                }
            }
        }
    )

    override val commandDescriptors: List<LSCommandDescriptor> = listOf(commandDescriptor)
}

private val LOG = fileLogger()

private class FakeModPsiUpdater(
    psiElement: KtElement,
) : ModPsiUpdater {
    var caret = psiElement.startOffset

    override fun <E : PsiElement?> getWritable(element: E?): E? {
        return element
    }

    override fun getOriginalFile(copyFile: PsiFile): PsiFile {
        return copyFile
    }

    override fun highlight(
        element: PsiElement,
        attributesKey: TextAttributesKey
    ) {
    }

    override fun highlight(
        range: TextRange,
        attributesKey: TextAttributesKey
    ) {
    }

    override fun rename(
        element: PsiNameIdentifierOwner,
        suggestedNames: List<String>
    ) {
    }

    override fun rename(
        element: PsiNamedElement,
        nameIdentifier: PsiElement?,
        suggestedNames: List<String>
    ) {
    }

    override fun trackDeclaration(declaration: PsiElement) {
    }

    override fun templateBuilder(): ModTemplateBuilder {
        return object : ModTemplateBuilder {
            override fun field(
                element: PsiElement,
                expression: Expression
            ): ModTemplateBuilder {
                return this
            }

            override fun field(
                element: PsiElement,
                varName: String,
                expression: Expression
            ): ModTemplateBuilder {
                return this
            }

            override fun field(
                element: PsiElement,
                rangeInElement: TextRange,
                varName: String,
                expression: Expression
            ): ModTemplateBuilder {
                return this
            }

            override fun field(
                element: PsiElement,
                varName: String,
                dependantVariableName: String,
                alwaysStopAt: Boolean
            ): ModTemplateBuilder {
                return this
            }

            override fun finishAt(offset: Int): ModTemplateBuilder {
                return this
            }

            override fun onTemplateFinished(templateFinishFunction: Function<in PsiFile, out ModCommand>): ModTemplateBuilder {
                return this
            }
        }
    }

    override fun cancel(errorMessage: @NlsContexts.Tooltip String) {
    }

    override fun showConflicts(conflicts: Map<PsiElement, ModShowConflicts.Conflict>) {
    }

    override fun message(message: @NlsContexts.Tooltip String) {
    }

    override fun select(element: PsiElement) {
    }

    override fun select(range: TextRange) {
    }

    override fun moveCaretTo(offset: Int) {
        caret = offset
    }

    override fun moveCaretTo(element: PsiElement) {
        caret = element.startOffset
    }

    override fun getCaretOffset(): Int {
        return caret
    }
}