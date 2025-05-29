// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.document.LSDocumentCommandExecutor
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.utils.createEditorWithCaret
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixService
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinCompilerDiagnosticsFixesCodeActionProvider : LSCodeActionProvider, LSCommandDescriptorProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(LSServer)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.shouldProvideKind(CodeActionKind.QuickFix)) return@flow
        val diagnosticData = params.diagnosticData<KotlinCompilerDiagnosticData>().ifEmpty { return@flow }

        val uri = params.textDocument.uri.uri
        withAnalysisContext {
            withWritableFile(uri) {
                runReadAction {
                    val file = params.textDocument.findVirtualFile() ?: return@runReadAction emptyList()
                    val quickFixService = KotlinQuickFixService.getInstance()
                    val ktFile = file.findPsiFile(project) as? KtFile ?: return@runReadAction emptyList()
                    val document = file.findDocument() ?: return@runReadAction emptyList()
                    analyze(ktFile) {
                        val kaDiagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                        if (kaDiagnostics.isEmpty()) return@analyze emptyList()
                        val editor = createEditorWithCaret(document, caretOffset = 0)
                        val result = mutableListOf<CodeAction>()
                        for (data in diagnosticData) {
                            val kaDiagnostic = kaDiagnostics.firstOrNull { data.data.matches(it) } ?: continue
                            with(quickFixService) {
                                result += getQuickFixesAsCodeActions(
                                    project,
                                    ktFile,
                                    editor,
                                    params.textDocument.uri,
                                    kaDiagnostic,
                                    data.diagnostic,
                                )
                            }
                        }
                        result
                    }
                }
            }
        }.forEach { emit(it) }
    }

    context(KaSession)
    private fun KotlinQuickFixService.getQuickFixesAsCodeActions(
        project: Project,
        file: KtFile,
        editor: Editor,
        documentUri: DocumentUri,
        kaDiagnostic: KaDiagnosticWithPsi<*>,
        lspDiagnostic: Diagnostic,
    ): List<CodeAction> {
        return (getQuickFixesWithCatchingFor(kaDiagnostic) + getLazyQuickFixesWithCatchingFor(kaDiagnostic))
            .mapNotNull { fixes ->
                fixes.getOrLogException { LOG.warn(it) }
            }
            .filter { intentionAction ->
                runCatching {
                    // this call may also compute some text inside the intention
                    intentionAction.isAvailable(project, editor, file)
                }.getOrLogException { LOG.warn(it) } ?: false
            }
            .map { intentionAction ->
                val fix = KotlinCompilerDiagnosticQuickfixData.createByIntentionAction(intentionAction)
                CodeAction(
                    intentionAction.text,
                    CodeActionKind.QuickFix,
                    diagnostics = listOf(lspDiagnostic),
                    command = Command(
                        commandDescriptor.title,
                        commandDescriptor.name,
                        arguments = listOf(
                            LSP.json.encodeToJsonElement(documentUri),
                            LSP.json.encodeToJsonElement(lspDiagnostic),
                            LSP.json.encodeToJsonElement(fix),
                        ),
                    ),
                )
            }
            .toList()
    }

    private val commandDescriptor = LSCommandDescriptor(
        "Kotlin Diagnostic Apply Fix",
        "kotlinDiagnostic.applyFix",
        LSDocumentCommandExecutor e@{ documentUri, otherArgs ->
            val lspDiagnostic = LSP.json.decodeFromJsonElement<Diagnostic>(otherArgs[0])
            val diagnosticData = lspDiagnostic.diagnosticData<KotlinCompilerDiagnosticData>() ?: return@e emptyList()
            val fix = LSP.json.decodeFromJsonElement<KotlinCompilerDiagnosticQuickfixData>(otherArgs[1])
            withAnalysisContext {
                val file = runReadAction { documentUri.findVirtualFile() } ?: return@withAnalysisContext emptyList()
                withWritableFile(documentUri.uri) {
                    PsiFileTextEditsCollector.collectTextEdits(file) { ktFile ->
                        check(ktFile is KtFile) { "PsiFile is not KtFile but ${ktFile}" }
                        val candidates = analyze(ktFile) {
                            val kaDiagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                            if (kaDiagnostics.isEmpty()) return@analyze emptySequence()

                            val kaDiagnostic = kaDiagnostics.firstOrNull { diagnosticData.matches(it) } ?: return@analyze emptySequence()

                            with(KotlinQuickFixService.getInstance()) {
                                getQuickFixesWithCatchingFor(kaDiagnostic) + getLazyQuickFixesWithCatchingFor(kaDiagnostic)
                            }
                                .mapNotNull { fix ->
                                    /* do not log exception here, they are already logged in getQuickFixesAsCodeActions */
                                    fix.getOrNull()
                                }

                        }
                        if (candidates.none()) return@collectTextEdits
                        val document = file.findDocument()!!
                        val editor = createEditorWithCaret(document, document.offsetByPosition(lspDiagnostic.range.start))

                        val fix = candidates.findCandidate(fix, editor, ktFile) ?: return@collectTextEdits
                        fix.invoke(project, editor, ktFile)
                    }
                }
            }
        }
    )

    context(LSAnalysisContext)
    private fun Sequence<IntentionAction>.findCandidate(
        fix: KotlinCompilerDiagnosticQuickfixData,
        editor: Editor,
        file: KtFile,
    ): IntentionAction? {
        for (fixAction in this) {
            // check class first as it's cheap
            if (fixAction::class.java.name != fix.intentionActionClass) continue
            // check the family name as it's fixed and should be computed on the fly
            if (fixAction.familyName != fix.familyName) continue
            // check availability next because it may compute the text
            if (!fixAction.isAvailable(project, editor, file)) continue
            if (fixAction.text != fix.text) continue
            return fixAction
        }
        return null
    }


    override val commandDescriptors: List<LSCommandDescriptor> = listOf(commandDescriptor)
}


private val LOG = logger<LSKotlinCompilerDiagnosticsFixesCodeActionProvider>()