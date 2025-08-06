// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion.rekot

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.util.endOffset
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.idea.base.projectStructure.getKaModule
import org.jetbrains.kotlin.idea.base.util.isImported
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.renderer.render
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices

/**
 * Taken from https://github.com/darthorimar/rekot
 */
internal object LSRekotBasedKotlinCompletionProviderImpl : LSCompletionProvider {
    override val uniqueId: String get() = this::class.java.name
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val supportsResolveRequest: Boolean get() = false

    context(_: LSServer)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList {
        if (!params.textDocument.isSource()) return CompletionList.EMPTY_COMPLETE
        return withAnalysisContext {
            runReadAction {
                val file = params.textDocument.findVirtualFile() ?: return@runReadAction EMPTY_COMPLETION_LIST
                val psiFile = file.findPsiFile(project) as? KtFile ?: return@runReadAction EMPTY_COMPLETION_LIST
                val document = file.findDocument() ?: return@runReadAction EMPTY_COMPLETION_LIST
                val offset = document.offsetByPosition(params.position)
                val fileForCompletion = createFileForCompletion(psiFile, offset)
                CompletionList(isIncomplete = true, items = createItems(fileForCompletion, document, psiFile, offset))
            }
        }
    }

    context(_: LSAnalysisContext)
    private fun createItems(
        fileForCompletion: KtFile,
        originalDocument: Document,
        originalFile: KtFile,
        offset: Int
    ): List<CompletionItem> {
        val imports = fileForCompletion.importDirectives.mapNotNull { it.importPath }
        return CompletionItemsProvider.createItems(fileForCompletion, offset)
            .map { item ->
                CompletionItem(
                    label = item.show.trim(),
                    labelDetails = getLabelDetails(item),

                    insertText = item.insert,
                    kind = when (item.tag) {
                        CompletionItemTag.FUNCTION -> CompletionItemKind.Function
                        CompletionItemTag.PROPERTY -> CompletionItemKind.Property
                        CompletionItemTag.CLASS -> CompletionItemKind.Class
                        CompletionItemTag.LOCAL_VARIABLE -> CompletionItemKind.Variable
                        CompletionItemTag.KEYWORD -> CompletionItemKind.Keyword
                    },
                    additionalTextEdits = when {
                        item is RekotCompletionItem.Declaration && item.import -> {
                            createAddImportTextEdit(originalFile, originalDocument, imports, item)?.let(::listOf)
                        }

                        else -> null
                    },
                    command = if (item.moveCaret < 0) vsCodeMoveLeftCommand(-item.moveCaret) else null
                )
            }
    }

    private fun getLabelDetails(item: RekotCompletionItem): CompletionItemLabelDetails? {
        return when (item) {
            is RekotCompletionItem.Declaration -> CompletionItemLabelDetails(
                detail = item.middle,
                description = item.tail
            )

            is RekotCompletionItem.Keyword -> null
        }
    }

    private fun createAddImportTextEdit(
        ktFile: KtFile,
        document: Document,
        imports: List<ImportPath>,
        item: RekotCompletionItem.Declaration
    ): TextEdit? {
        val import = item.fqName ?: return null
        val importPath = ImportPath(FqName(import), false)
        if (importPath.isImported(defaultImports, excludedFqNames = emptyList())) return null
        if (importPath.isImported(imports, excludedFqNames = emptyList())) return null
        val importString = "\nimport ${importPath.fqName.render()}"

        val anchor = ktFile.importList ?: ktFile.packageDirective
        ?: return TextEdit(Range.empty(Position.ZERO), importString)

        return TextEdit(
            Range.empty(document.positionByOffset(anchor.endOffset)),
            importString,
        )
    }

    private val defaultImports = JvmPlatformAnalyzerServices.getDefaultImports(includeLowPriorityImports = true)


    private fun createFileForCompletion(original: KtFile, offset: Int): KtFile {
        val textWithInsertedFakeIdentifier = original.text.replaceRange(offset, offset, COMPLETION_FAKE_IDENTIFIER)
        val copyKtFile =
            KtPsiFactory(original.project, markGenerated = true, eventSystemEnabled = true).createFile(textWithInsertedFakeIdentifier)
        copyKtFile.contextModule = original.getKaModule(original.project, useSiteModule = null)
        copyKtFile.originalFile = original
        return copyKtFile
    }

    private val EMPTY_COMPLETION_LIST = CompletionList(isIncomplete = false, items = emptyList())

    private fun vsCodeMoveLeftCommand(to: Int?): Command {
        val args = buildMap {
            put("to", JsonPrimitive("left"))
            to?.let { put("value", JsonPrimitive(it)) }
        }
        return Command("Adjust Cursor", "cursorMove", listOf(JsonObject(args)))
    }
}