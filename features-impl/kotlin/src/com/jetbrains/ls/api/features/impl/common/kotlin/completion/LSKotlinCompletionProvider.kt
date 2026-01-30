// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.completion.insertCompletion
import com.intellij.codeInsight.completion.performCompletion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.completion.getSortedFieldByIndex
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase.LSMarkdownDocProvider.Companion.getMarkdownDoc
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.snapshot.api.impl.core.CompletionItemId
import com.jetbrains.ls.snapshot.api.impl.core.CompletionItemWithObject
import com.jetbrains.ls.snapshot.api.impl.core.LatestCompletionSessionEntity
import com.jetbrains.ls.snapshot.api.impl.core.initializeParams
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import fleet.kernel.change
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.withDanglingFileResolutionMode
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

internal object LSKotlinCompletionProvider : LSCompletionProvider, LSCommandDescriptorProvider {
    override val supportsResolveRequest: Boolean
        get() = true

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList {
        return if (!params.textDocument.isSource()) {
            CompletionList.EMPTY
        } else {
            val itemsWithObjects = server.withAnalysisContext(params.textDocument.uri.uri) {
                readAction {
                    params.textDocument.findVirtualFile()?.let { file ->
                        file.findPsiFile(project)?.let { psiFile ->
                            file.findDocument()?.offsetByPosition(params.position)?.let { offset ->
                                psiFile to offset
                            }
                        }
                    }
                }?.let { (psiFile, offset) ->
                    val completionProcess = edtWriteAction {
                        createCompletionProcess(
                            project = project,
                            file = psiFile,
                            offset = offset,
                            invocationCount = params.computeInvocationCount()
                        )
                    }
                    readAction {
                        val lookupElements = performCompletion(completionProcess)
                        lookupElements.mapIndexed { i, lookup ->
                            val lookupPresentation = LookupElementPresentation().also {
                                lookup.renderElement(it)
                            }

                            val itemMatcher = completionProcess.arranger.itemMatcher(lookup)
                            val obj = CompletionData(params, lookup, itemMatcher)

                            val key = LatestCompletionSessionEntity.nextId()
                            CompletionItemWithObject(
                                item = CompletionItem(
                                    label = lookupPresentation.itemText ?: lookup.lookupString,
                                    sortText = getSortedFieldByIndex(i),
                                    labelDetails = CompletionItemLabelDetails(
                                        detail = lookupPresentation.tailText,
                                        description = lookupPresentation.typeText,
                                    ),
                                    kind = lookup.psiElement?.let { LSCompletionItemKindProvider.getKind(it) },
                                    textEdit = CompletionItem.Edit.emptyAtPosition(params.position),
                                    command = Command(
                                        "Apply Completion",
                                        command = APPLY_COMPLETION_COMMAND,
                                        arguments = listOf(key.toJson())
                                    ),
                                    data = JsonObject(
                                        mapOf(
                                            "KotlinCompletionItemKey" to key.toJson(),
                                            ResolveDataWithConfigurationEntryId::configurationEntryId.name to LSP.json.encodeToJsonElement(
                                                uniqueId
                                            )
                                        )
                                    ),
                                ),
                                key = key,
                                obj = obj
                            )
                        }
                    }
                } ?: emptyList()
            }
            change {
                LatestCompletionSessionEntity.replace(itemsWithObjects)
            }
            CompletionList(isIncomplete = true, items = itemsWithObjects.map { it.item })
        }
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun resolveCompletion(completionItem: CompletionItem): CompletionItem? {
        val completionDataKey = completionItem.data?.jsonObject?.get("KotlinCompletionItemKey") ?: return completionItem

        return (LatestCompletionSessionEntity.obj(CompletionItemId.fromJson(completionDataKey)) as CompletionData?)?.let { completionData ->
            server.withAnalysisContext {
                val completionItem = completionItem.copy(
                    documentation = readAction { computeDocumentation(completionData.lookup) },
                )
                // https://youtrack.jetbrains.com/issue/LSP-319/Fix-completion-in-Air
                val isAir = checkNotNull(initializeParams()).clientInfo?.name?.equals("JetBrains Air") ?: false
                if (isAir) {
                    val insRes = applyCompletion(completionData)
                    completionItem.copy(
                        additionalTextEdits = insRes.edits,
                        command = Command(
                            title = "Move cursor",
                            command = "cursorMove",
                            arguments = listOf(
                                buildJsonObject {
                                    put("to", "offset")
                                    put("value", insRes.caretOffset)
                                }
                            )
                        )
                    )
                } else {
                    completionItem
                }
            }
        }
    }

    private fun CompletionParams.computeInvocationCount(): Int {
        // todo should be based on CompletionParams.context.triggerKind
        return 1
    }

    const val APPLY_COMPLETION_COMMAND: String = "jetbrains.kotlin.completion.apply"

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = "Apply Completion Item",
                name = APPLY_COMPLETION_COMMAND,
                executor = { arguments ->
                    require(arguments.size == 1) { "Expected 1 argument, got: ${arguments.size}" }
                    val id = arguments[0]
                    when (val completionData = LatestCompletionSessionEntity.obj(CompletionItemId.fromJson(id)) as CompletionData?) {
                        null -> {
                            lspClient.notify(
                                notificationType = ShowMessageNotificationType,
                                params = ShowMessageParams(MessageType.Error, "Your completion session has expired, please try again"),
                            )
                        }

                        else -> {
                            val insertionResult = contextOf<LSServer>().withAnalysisContext {
                                applyCompletion(completionData)
                            }
                            lspClient.request(
                                ApplyEditRequests.ApplyEdit,
                                ApplyWorkspaceEditParams(
                                    label = null,
                                    edit = WorkspaceEdit(
                                        changes = mapOf(completionData.params.textDocument.uri to insertionResult.edits)
                                    )
                                )
                            )
                            lspClient.request(
                                ShowDocument,
                                ShowDocumentParams(
                                    uri = completionData.params.textDocument.uri.uri,
                                    external = false,
                                    takeFocus = true,
                                    selection = Range(insertionResult.caretPosition, insertionResult.caretPosition)
                                )
                            )
                        }
                    }

                    JsonPrimitive(true)
                }
            )
        )

    fun computeDocumentation(lookup: LookupElement): StringOrMarkupContent? {
        return lookup.psiElement
            ?.let { getMarkdownDoc(it) }
            ?.let { StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, it)) }
    }

    data class CompletionData(val params: CompletionParams, val lookup: LookupElement, val itemMatcher: PrefixMatcher)

    override val uniqueId: LSUniqueConfigurationEntry.UniqueId = LSUniqueConfigurationEntry.UniqueId("KotlinCompletionProvider")
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    private data class CompletionInsertionResult(val edits: List<TextEdit>, val caretPosition: Position, val caretOffset: Int)

    context(analysisContext: LSAnalysisContext)
    private fun applyCompletion(completionData: CompletionData): CompletionInsertionResult =
        invokeAndWaitIfNeeded {
            runWriteAction {
                val physicalVirtualFile = requireNotNull(completionData.params.textDocument.findVirtualFile()) {
                    "virtual file not found for ${completionData.params.textDocument}"
                }
                val physicalPsiFile = requireNotNull(physicalVirtualFile.findPsiFile(project)) {
                    "psi file not found for $physicalVirtualFile"
                }
                val initialText = physicalPsiFile.text

                withFileForModification(
                    physicalPsiFile,
                ) { fileForModification ->
                    val document = fileForModification.fileDocument
                    val caretBefore = document.offsetByPosition(completionData.params.position)
                    val completionProcess = createCompletionProcess(project, fileForModification, caretBefore)
                    completionProcess.arranger.registerMatcher(
                        completionData.lookup,
                        CamelHumpMatcher(completionData.itemMatcher.prefix)
                    )
                    insertCompletion(project, fileForModification, completionData.lookup, completionProcess.parameters!!)
                    val edits = TextEditsComputer.computeTextEdits(initialText, fileForModification.text)
                    val caretAfter = completionProcess.caret.offset
                    CompletionInsertionResult(edits, document.positionByOffset(caretAfter), caretAfter)
                }
            }
        }


    @OptIn(KaImplementationDetail::class)
    context(analysisContext: LSAnalysisContext)
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
}
