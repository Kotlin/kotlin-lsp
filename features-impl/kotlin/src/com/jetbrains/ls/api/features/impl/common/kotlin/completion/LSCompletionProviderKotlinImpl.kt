// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.completion.insertCompletion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.completion.CompletionItemData
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider.LSMarkdownDocProvider.Companion.getMarkdownDoc
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.vscode.VsCodeCommands
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.withDanglingFileResolutionMode
import org.jetbrains.kotlin.idea.completion.api.serialization.lookup.LookupModelConverter
import org.jetbrains.kotlin.idea.completion.impl.k2.serializableInsertionHandlerSerializersModule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

object LSCompletionProviderKotlinImpl : LSAbstractCompletionProvider() {
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId = LSUniqueConfigurationEntry.UniqueId("KotlinCompletionProvider")
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)


    override fun createAdditionalData(
        lookupElement: LookupElement,
        itemMatcher: PrefixMatcher
    ): JsonElement? {
        val model = LookupModelConverter.serializeLookupElementForInsertion(lookupElement, lookupModelConverterConfig) ?: return null
        val data = KotlinCompletionLookupItemData(itemMatcher.prefix, model)

        return json.encodeToJsonElement(KotlinCompletionLookupItemData.serializer(), data)
    }

    private val lookupModelConverterConfig = LookupModelConverter.Config(safeMode = true)


    @OptIn(KaImplementationDetail::class)
    context(_: LSServer, _: LspHandlerContext)
    override suspend fun resolveCompletion(completionItem: CompletionItem): CompletionItem? {
        val data = json.decodeFromJsonElement<CompletionItemData>(completionItem.data ?: return null)
        val kotlinData = json.decodeFromJsonElement<KotlinCompletionLookupItemData>(data.additionalData)
        val params = data.params

        return withAnalysisContext {
            invokeAndWaitIfNeeded {
                runWriteAction {
                    val physicalVirtualFile = params.textDocument.findVirtualFile() ?: return@runWriteAction null
                    val physicalPsiFile = physicalVirtualFile.findPsiFile(project) ?: return@runWriteAction null
                    val initialText = physicalPsiFile.text

                    withFileForModification(
                        physicalPsiFile,
                    ) { fileForModification ->
                        val lookup = LookupModelConverter.deserializeLookupElementForInsertion(kotlinData.model, project)
                        val document = fileForModification.fileDocument

                        val caretBefore = document.offsetByPosition(params.position)

                        val completionProcess = createCompletionProcess(project, fileForModification, caretBefore)
                        completionProcess.arranger.registerMatcher(lookup, CamelHumpMatcher(kotlinData.prefix))
                        insertCompletion(project, fileForModification, lookup, completionProcess.parameters!!)

                        val edits = TextEditsComputer.computeTextEdits(initialText, fileForModification.text)

                        val caretOffset = run {
                            val caretAfter = completionProcess.caret.offset
                            caretAfter - caretBefore - edits.countAddedCharactersToLine(params.position.line)
                        }

                        completionItem.copy(
                            additionalTextEdits = edits,
                            command = VsCodeCommands.moveCursorCommand(caretOffset, completionProcess.caret.offset),
                            documentation = computeDocumentation(lookup),
                        )
                    }
                }
            }
        }
    }

    @OptIn(KaImplementationDetail::class)
    context(_: LSAnalysisContext)
    private fun <T> withFileForModification(
        physicalPsiFile: PsiFile,
        action: (fileForModification: KtFile) -> T
    ): T {
        val initialText = physicalPsiFile.text

        val ktPsiFactory = KtPsiFactory(project, eventSystemEnabled = true)
        val fileForModification = ktPsiFactory.createFile(physicalPsiFile.name, initialText)
        fileForModification.originalFile = physicalPsiFile

        return withDanglingFileResolutionMode(fileForModification, KaDanglingFileResolutionMode.IGNORE_SELF) {
            action(fileForModification)
        }
    }


    private fun computeDocumentation(lookup: LookupElement): StringOrMarkupContent? {
        return lookup.psiElement
            ?.let { getMarkdownDoc(it) }
            ?.let { StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, it)) }
    }

    private fun List<TextEdit>.countAddedCharactersToLine(line: Int): Int {
        return this
            .filter { it.range.isSingleLine() && it.range.start.line == line }
            .sumOf { it.countAddedChars() }
    }

    private fun TextEdit.countAddedChars(): Int {
        return this.newText.length + this.range.end.character - this.range.start.character
    }

    private val json = Json(LSP.json) {
        serializersModule = SerializersModule {
            include(LSP.json.serializersModule)
            include(serializableInsertionHandlerSerializersModule)
        }
    }
}
