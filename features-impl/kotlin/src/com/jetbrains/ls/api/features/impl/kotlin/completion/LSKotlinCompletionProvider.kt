// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.completion

import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.completion.LSCompletionProviderHelper
import com.jetbrains.ls.api.features.impl.common.completion.LSModCompletion
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.withDanglingFileResolutionMode
import org.jetbrains.kotlin.psi.KtPsiFactory

internal object LSKotlinCompletionProvider : LSCompletionProvider, LSCommandDescriptorProvider {

    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId = LSUniqueConfigurationEntry.UniqueId("KotlinCompletionProvider")
    override val supportsResolveRequest: Boolean = true

    val fileForModificationProvider = object : LSCompletionProviderHelper.FileForModificationProvider {
        @OptIn(KaImplementationDetail::class)
        context(analysisContext: LSAnalysisContext)
        override fun <T> withFileForModification(physicalPsiFile: PsiFile, action: (fileForModification: PsiFile) -> T): T {
            val ktPsiFactory = KtPsiFactory(project, eventSystemEnabled = true)
            val fileForModification = ktPsiFactory.createFile(physicalPsiFile.name, physicalPsiFile.text)
            fileForModification.originalFile = physicalPsiFile

            return withDanglingFileResolutionMode(fileForModification, KaDanglingFileResolutionMode.IGNORE_SELF) {
                action(fileForModification)
            }
        }
    }
    private val helper = LSCompletionProviderHelper(
        language = LSKotlinLanguage,
        uniqueId = uniqueId,
        applyCompletionCommandKey = "jetbrains.kotlin.completion.apply",
        completionDataKey = "KotlinCompletionItemKey",
    )

    override val commandDescriptors: List<LSCommandDescriptor> = helper.createCommandDescriptors(fileForModificationProvider)

    /**
     * An injection whose language has ModCompletion providers of its own (JPQL, for example) completes through them on the injected
     * file, and its items edit the host. The host itself and the injections without such providers complete through the legacy helper.
     * [resolveCompletion] needs no route of its own: the helper returns an item without its data key unchanged, and ModCompletion items
     * carry no data.
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList =
        LSModCompletion.provideInjectedCompletion(params) ?: helper.provideCompletion(params)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun resolveCompletion(completionItem: CompletionItem): CompletionItem? =
        helper.resolveCompletion(completionItem, fileForModificationProvider)
}
