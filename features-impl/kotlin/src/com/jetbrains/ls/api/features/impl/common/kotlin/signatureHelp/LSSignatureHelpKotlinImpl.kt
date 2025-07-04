// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.signatureHelp

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findPsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider.LSMarkdownDocProvider.Companion.getMarkdownDocAsStringOrMarkupContent
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.signatureHelp.LSSignatureHelpProvider
import com.jetbrains.lsp.protocol.ParameterInformation
import com.jetbrains.lsp.protocol.SignatureHelp
import com.jetbrains.lsp.protocol.SignatureHelpParams
import com.jetbrains.lsp.protocol.SignatureInformation
import org.jetbrains.kotlin.idea.parameterInfo.KotlinHighLevelArrayAccessParameterInfoHandler
import org.jetbrains.kotlin.idea.parameterInfo.KotlinHighLevelFunctionParameterInfoHandler
import org.jetbrains.kotlin.idea.parameterInfo.KotlinHighLevelLambdaParameterInfoHandler
import org.jetbrains.kotlin.idea.parameterInfo.KotlinHighLevelParameterInfoWithCallHandlerBase
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

object LSSignatureHelpKotlinImpl : LSSignatureHelpProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    @Suppress("UNCHECKED_CAST")
    private val handlers: List<KotlinHighLevelParameterInfoWithCallHandlerBase<KtElement, out KtElement>> = listOf(
        KotlinHighLevelFunctionParameterInfoHandler(),
        KotlinHighLevelLambdaParameterInfoHandler(),
        KotlinHighLevelArrayAccessParameterInfoHandler(),
    ) as List<KotlinHighLevelParameterInfoWithCallHandlerBase<KtElement, out KtElement>>

    context(_: LSServer)
    override suspend fun getSignatureHelp(params: SignatureHelpParams): SignatureHelp? {
        return withAnalysisContext {
            runReadAction r@{
                val file = params.findVirtualFile() ?: return@r null
                val ktFile = file.findPsiFile() as? KtFile ?: return@r null
                val document = file.findDocument() ?: return@r null
                val offset = document.offsetByPosition(params.position)
                for (handler in handlers) {
                    collectByHandler(handler, ktFile, offset)?.let { return@r it }
                }
                null
            }
        }
    }

    private fun collectByHandler(
        handler: KotlinHighLevelParameterInfoWithCallHandlerBase<KtElement, out KtElement>,
        ktFile: KtFile,
        offset: Int
    ): SignatureHelp? {
        val argumentList = handler.findElementForParameterInfo(ktFile, offset) ?: return null

        val callInfos = handler.createCallInfos(argumentList, handler.getCurrentArgumentIndex(offset, argumentList)) ?: return null
        val signatures = callInfos.mapNotNull { info ->
            info.toSignatureInformation(handler, offset, argumentList, isSingleCandidate = callInfos.size == 1)
        }
        return SignatureHelp(
            signatures,
            callInfos.indexOfFirstOrNull { it.shouldHighlightGreen },
            activeParameter = null,
        )
    }

    private fun <TArgumentList : KtElement> KotlinHighLevelParameterInfoWithCallHandlerBase.CallInfo.toSignatureInformation(
        handler: KotlinHighLevelParameterInfoWithCallHandlerBase<TArgumentList, *>,
        offset: Int,
        argumentList: TArgumentList,
        isSingleCandidate: Boolean,
    ): SignatureInformation? {
        val signatureModel = getSignatureModel(handler, offset, argumentList, isSingleCandidate) ?: return null
        return SignatureInformation(
            label = signatureModel.text,
            documentation = target?.let { getMarkdownDocAsStringOrMarkupContent(it) },
            parameters = signatureModel.parts.mapIndexedNotNull { i, p ->
                if (p !is KotlinHighLevelParameterInfoWithCallHandlerBase.SignaturePart.Parameter) return@mapIndexedNotNull null
                val range = signatureModel.getRange(i)
                ParameterInformation(ParameterInformation.Label.RangeLabel(range.first, range.second), documentation = null)
            },
            activeParameter = signatureModel.parts
                .filterIsInstance<KotlinHighLevelParameterInfoWithCallHandlerBase.SignaturePart.Parameter>()
                .indexOfFirstOrNull { it.isHighlighted },
        )
    }

    private fun <TArgumentList : KtElement> KotlinHighLevelParameterInfoWithCallHandlerBase.CallInfo.getSignatureModel(
        handler: KotlinHighLevelParameterInfoWithCallHandlerBase<TArgumentList, *>,
        offset: Int,
        argumentList: TArgumentList,
        isSingleCandidate: Boolean
    ): KotlinHighLevelParameterInfoWithCallHandlerBase.SignatureModel? {
        val uiModel = with(handler) {
            toUiModel(handler.getCurrentArgumentIndex(offset, argumentList), appendNoParametersMessage = !isSingleCandidate)
        } ?: return null
        return KotlinHighLevelParameterInfoWithCallHandlerBase.SignatureModel(
            buildList {
                add(KotlinHighLevelParameterInfoWithCallHandlerBase.SignaturePart.Text(representation.beforeParameters + "("))
                addAll(uiModel.signatureModel.parts)
                add(KotlinHighLevelParameterInfoWithCallHandlerBase.SignaturePart.Text(")" + representation.afterParameters))
            }
        )
    }
}

private inline fun <T> List<T>.indexOfFirstOrNull(predicate: (T) -> Boolean): Int? {
    return indexOfFirst(predicate).takeIf { it >= 0 }
}