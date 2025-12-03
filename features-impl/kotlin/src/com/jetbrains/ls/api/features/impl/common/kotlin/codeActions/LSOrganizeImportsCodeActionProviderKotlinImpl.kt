// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.codeActions

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.codeActions.LSSimpleCodeActionProvider
import com.jetbrains.ls.api.features.codeActions.LSSimpleCodeActionProvider.NoData
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.TextEdit
import kotlinx.serialization.KSerializer
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinOptimizeImportsFacility
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

internal object LSOrganizeImportsCodeActionProviderKotlinImpl : LSSimpleCodeActionProvider<NoData>() {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)

    override val title: String get() = "Organize Imports"
    override val commandName: String get() = "kotlin.organize.imports"
    override val kind: CodeActionKind get() = CodeActionKind.SourceOrganizeImports
    override val dataSerializer: KSerializer<NoData> get() = NoData.serializer()

    context(_: LSServer, _: LSAnalysisContext)
    override fun getData(file: VirtualFile, params: CodeActionParams): NoData? {
        if (file.findPsiFile(project) !is KtFile) return null
        return NoData
    }

    context(_: LSServer, _: LSAnalysisContext)
    override fun execute(
        file: VirtualFile,
        data: NoData
    ): List<TextEdit> {
        val psiFile = file.findPsiFile(project) as? KtFile ?: return emptyList()
        val currentImportList = psiFile.importList ?: return emptyList()

        val optimizedImports = generateOptimizedImports(psiFile) ?: return emptyList()
        val document = file.findDocument() ?: return emptyList()
        val replaceImports = TextEdit(
            currentImportList.textRange.toLspRange(document).toTheLineEndWithLineBreak(),
            newText = optimizedImports,
        )
        return listOf(replaceImports)
    }

    private fun generateOptimizedImports(psiFile: KtFile): String? {
        val optimizeImportsFacility = KotlinOptimizeImportsFacility.getInstance()
        val analysisResult = optimizeImportsFacility.analyzeImports(psiFile) ?: return null
        val preparedImports = optimizeImportsFacility.prepareOptimizedImports(psiFile, analysisResult) ?: return null
        return generateImportsListAsString(psiFile, preparedImports)
    }

    private fun generateImportsListAsString(file: KtFile, imports: Iterable<ImportPath>): String {
        if (imports.none()) return ""
        val psiFactory = KtPsiFactory(file.project)
        return buildString {
            for (importPath in imports) {
                val directive = psiFactory.createImportDirective(importPath)
                appendLine(directive.text)
            }
        }
    }
}