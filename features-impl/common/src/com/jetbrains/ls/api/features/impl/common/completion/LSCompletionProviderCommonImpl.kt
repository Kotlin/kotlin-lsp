package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.insertCompletion
import com.intellij.codeInsight.completion.performCompletion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.completion.CompletionItemData
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider.LSMarkdownDocProvider.Companion.getMarkdownDoc
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector.FileForModificationFactory
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class LSCompletionProviderCommonImpl(
    override val uniqueId: String,
    override val supportedLanguages: Set<LSLanguage>,
) : LSCompletionProvider {
    override val supportsResolveRequest: Boolean get() = true

    context(LSServer)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList {
        return withAnalysisContext(params.textDocument.uri.uri) {
            val file = params.textDocument.findVirtualFile() ?: return@withAnalysisContext EMPTY_COMPLETION_LIST
            val psiFile = file.findPsiFile(project) ?: return@withAnalysisContext EMPTY_COMPLETION_LIST
            val document = file.findDocument() ?: return@withAnalysisContext EMPTY_COMPLETION_LIST
            val offset = document.offsetByPosition(params.position)
            val completionProcess = createCompletionProcess(project, psiFile, offset)
            val lookupElements = performCompletion(completionProcess)
            val completionItems = lookupElements.mapIndexed { i, lookup ->
                val lookupPresentation = LookupElementPresentation().also { lookup.renderElement(it) }
                val data = CompletionItemData(this.uniqueId, params, lookup.lookupString, lookupElementPointer(lookup))
                CompletionItem(
                    label = lookupPresentation.itemText ?: lookup.lookupString,
                    sortText = getSortedFieldByIndex(i),
                    labelDetails = CompletionItemLabelDetails(
                        detail = lookupPresentation.tailText,
                        description = lookupPresentation.typeText,
                    ),
                    kind = lookup.psiElement?.let { LSCompletionItemKindProvider.getKind(it) },
                    textEdit = emptyTextEdit(params.position),
                    data = LSP.json.encodeToJsonElement(data)
                )
            }
            CompletionList(
                isIncomplete = false,
                items = completionItems,
            )
        }
    }

    context(LSServer)
    override suspend fun resolveCompletion(completionItem: CompletionItem): CompletionItem? {
        val data = completionItem.data ?: return null
        val (_, params, lookupString, pointer) = try {
            LSP.json.decodeFromJsonElement<CompletionItemData>(data)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        return withAnalysisContext(params.textDocument.uri.uri) {
            val file = params.textDocument.findVirtualFile() ?: return@withAnalysisContext null
            val originalPsiFile = file.findPsiFile(project) ?: return@withAnalysisContext null
            val psiFile = FileForModificationFactory.forLanguage(originalPsiFile.language)
                .createFileForModifications(originalPsiFile, setOriginalFile = true)
            val document = file.findDocument() ?: return@withAnalysisContext null
            val offset = document.offsetByPosition(params.position)

            val completionProcess = createCompletionProcess(project, psiFile, offset)
            val lookupElements = performCompletion(completionProcess)
            val lookup = lookupElements.firstOrNull { it.lookupString == lookupString && lookupElementPointer(it) == pointer } ?: return@withAnalysisContext null
            runCatching {
                insertCompletion(project, psiFile, lookup, completionProcess.parameters!!)
            }.getOrLogException { LOG.error("Failed to apply completion", it) }
            val oldText = originalPsiFile.text
            val newText = psiFile.viewProvider.contents.toString() // TODO: Replace with psiFile.text when document commit is implemented
            var textEdits = computeTextEdits(oldText, newText)
                // TODO: A dirty hack to make imports work before we implement document commit
                .map { if (it.range.start.character == 0 && it.newText.startsWith("import ")) it.copy(newText = it.newText + '\n') else it }

            // A client-side command, thus inherently client-specific. At the moment, we support only VS Code.
            val command = when {
                textEdits.isEmpty() -> null
                textEdits.last().newText.endsWith(")") -> VSCODE_MOVE_ONE_CHARACTER_LEFT
                textEdits.last().newText.endsWith(" }") -> VSCODE_MOVE_TWO_CHARACTERS_LEFT
                else -> null
            }

            val documentation = lookup.psiElement
                ?.let {  getMarkdownDoc(it) }
                ?.let { StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, it)) }

            completionItem.copy(
                additionalTextEdits = textEdits,
                command = command,
                documentation = documentation
            )
        }
    }

    companion object {
        private val LOG = logger<LSCompletionProviderCommonImpl>()

        private val EMPTY_COMPLETION_LIST = CompletionList(isIncomplete = false, items = emptyList())

        /**
         * According to the LSP spec, completion items are sorted by `sortText` field with string comparison.
         *
         * As items are already sorted by kotlin completion, we just generate string which will be sorted the same way
         */
        private fun getSortedFieldByIndex(index: Int): String {
            return index.toString().padStart(MAX_INT_DIGITS_COUNT, '0')
        }

        private const val MAX_INT_DIGITS_COUNT = Int.MAX_VALUE.toString().length

        private fun emptyTextEdit(position: Position): TextEditOrInsertReplaceEdit {
            val range = Range(position, position)
            return TextEditOrInsertReplaceEdit(InsertReplaceEdit("", range, range))
        }

        context(LSServer, LSAnalysisContext)
        private fun lookupElementPointer(lookup: LookupElement): PsiSerializablePointer? =
            lookup.psiElement?.let { psiElement ->
                psiElement.containingFile?.virtualFile?.let { file -> 
                    PsiSerializablePointer.create(psiElement, file)
                }
            }

        private val VSCODE_MOVE_ONE_CHARACTER_LEFT =
            Command("Adjust Cursor", "cursorMove", listOf(JsonObject(mapOf("to" to JsonPrimitive("left")))))
        private val VSCODE_MOVE_TWO_CHARACTERS_LEFT =
            Command("Adjust Cursor", "cursorMove", listOf(JsonObject(mapOf("to" to JsonPrimitive("left"), "value" to JsonPrimitive(2)))))
    }
}

