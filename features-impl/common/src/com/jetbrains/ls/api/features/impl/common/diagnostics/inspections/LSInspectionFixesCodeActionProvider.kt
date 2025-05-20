package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.document.LSDocumentCommandExecutor
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class LSInspectionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
) : LSCodeActionProvider, LSCommandDescriptorProvider {

    context(LSServer)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.shouldProvideKind(CodeActionKind.QuickFix)) return@flow
        val diagnosticData = params.diagnosticData<InspectionDiagnosticData>().ifEmpty { return@flow }

        withAnalysisContext(params.textDocument.uri.uri) {
            val file = params.textDocument.findVirtualFile() ?: return@withAnalysisContext emptyList()
            diagnosticData.flatMap { data ->
                data.data.fixes.map { quickFix ->
                    CodeAction(
                        title = quickFix.name,
                        kind = CodeActionKind.QuickFix,
                        diagnostics = listOf(data.diagnostic),
                        command = Command(
                            commandDescriptor.title,
                            commandDescriptor.name,
                            arguments = listOf(
                                LSP.json.encodeToJsonElement(file.uri),
                                LSP.json.encodeToJsonElement(quickFix),
                            ),
                        ),
                    )
                }
            }
        }.forEach { emit(it) }
    }

    private val commandDescriptor = LSCommandDescriptor(
        "Inspection Apply Fix",
        "inspection.applyFix",
        LSDocumentCommandExecutor { documentUri, otherArgs ->
            val fix = LSP.json.decodeFromJsonElement<InspectionQuickfixData>(otherArgs[0])
            withAnalysisContext(documentUri.uri) {
                val file = documentUri.findVirtualFile() ?: return@withAnalysisContext emptyList()

                PsiFileTextEditsCollector.collectTextEdits(file) { psiFile ->
                    val (fix, descriptor) = findRealFix(fix, psiFile) ?: return@collectTextEdits
                    fix.applyFix(project, descriptor)
                }
            }
        }
    )

    private data class QuickFixWithDescriptor(
        val quickFix: QuickFix<CommonProblemDescriptor>,
        val descriptor: ProblemDescriptor,
    )

    context(LSAnalysisContext)
    private fun findRealFix(
        fix: InspectionQuickfixData,
        psiFile: PsiFile,
    ): QuickFixWithDescriptor? {
        // TODO unsecure!!!
        val inspection = Class.forName(fix.inspectionClass).getConstructor().newInstance() as LocalInspectionTool

        val holder = ProblemsHolder(InspectionManagerEx(project), psiFile, /* onTheFly = */ false)
        val element = fix.basePsiElement.restore(psiFile) ?: return null
        element.accept(inspection.buildVisitor(holder, /* isOnTheFly = */ false))
        for (result in holder.results) {
            for (resultFix in result.fixes.orEmpty()) {
                if (fix.matches(resultFix)) {
                    return QuickFixWithDescriptor(resultFix, result)
                }
            }
        }
        return null
    }


    override val commandDescriptors: List<LSCommandDescriptor> = listOf(commandDescriptor)
}