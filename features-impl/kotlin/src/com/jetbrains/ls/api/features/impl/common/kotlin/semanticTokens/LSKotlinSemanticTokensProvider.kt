// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.semanticTokens

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.semanticTokens.*
import com.jetbrains.ls.api.features.utils.allNonWhitespaceChildren
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.SemanticTokensParams
import com.jetbrains.lsp.protocol.SemanticTokensRangeParams
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

private val LOG = logger<LSKotlinSemanticTokensProvider>()

@ApiStatus.Internal
object LSKotlinSemanticTokensProvider : LSSemanticTokensProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    override fun createRegistry(): LSSemanticTokenRegistry {
        return LSSemanticTokenRegistry(LSSemanticTokenTypePredefined.ALL, LSSemanticTokenModifierPredefined.ALL)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun full(params: SemanticTokensParams): List<LSSemanticTokenWithRange> {
        return getTokens(params.textDocument, range = null)

    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun range(params: SemanticTokensRangeParams): List<LSSemanticTokenWithRange> {
        return getTokens(params.textDocument, params.range)
    }

    /**
     * @param range `null` means tokens from the whole file`
     */
    context(server: LSServer)
    private suspend fun getTokens(textDocument: TextDocumentIdentifier, range:Range?): List<LSSemanticTokenWithRange> {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = textDocument.findVirtualFile() ?: return@readAction emptyList()
                val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val leafs = ktFile.allNonWhitespaceChildren(document, range)
                if (leafs.isEmpty()) return@readAction emptyList()
                analyze(ktFile) {
                    leafs.mapNotNull { element ->
                        element.getRangeWithToken(document)
                    }
                }
            }
        }
    }

    context(server: LSServer, kaSession: KaSession)
    private fun PsiElement.getRangeWithToken(document: Document): LSSemanticTokenWithRange? = try {
        val psiElement = this
        with(kaSession) {
            when (psiElement) {
                is KtSimpleNameExpression -> {
                    val resolvedTo = psiElement.mainReference.resolveToSymbol() ?: return null
                    val token = getRangeWithToken(resolvedTo) ?: return null
                    LSSemanticTokenWithRange(token, textRange.toLspRange(document))
                }

                is KtParameter if isFunctionTypeParameter -> null

                is KtNamedDeclaration -> {
                    // todo should probably be implemented on the vscode side
                    //  or, if on the lsp server side, then it should work on PSI, without resolve which is faster
                    val nameIdentifier = psiElement.nameIdentifier ?: return null
                    val token = getRangeWithToken(psiElement.symbol) ?: return null
                    LSSemanticTokenWithRange(
                        token.withModifiers(LSSemanticTokenModifierPredefined.DECLARATION),
                        nameIdentifier.textRange.toLspRange(document),
                    )
                }

                else -> null
            }
        }
    }
    catch (e: CancellationException) {
        throw e
    }
    catch (e: Throwable) {
        LOG.error(e)
        null
    }

    context(server: LSServer, kaSession: KaSession)
    private fun getRangeWithToken(symbol: KaSymbol): LSSemanticToken? = with(kaSession) {
        val type = when (symbol) {
            is KaPackageSymbol -> LSSemanticTokenTypePredefined.NAMESPACE
            is KaTypeAliasSymbol -> return symbol.expandedType.expandedSymbol?.let { getRangeWithToken(it) }
            is KaTypeParameterSymbol -> LSSemanticTokenTypePredefined.TYPE_PARAMETER
            is KaClassSymbol -> when (symbol.classKind) {
                KaClassKind.CLASS -> when (symbol) {
                    is KaNamedClassSymbol if symbol.isData -> LSSemanticTokenTypePredefined.STRUCT
                    else -> LSSemanticTokenTypePredefined.CLASS
                }

                KaClassKind.ENUM_CLASS -> LSSemanticTokenTypePredefined.ENUM
                KaClassKind.INTERFACE -> LSSemanticTokenTypePredefined.INTERFACE
                KaClassKind.ANNOTATION_CLASS -> LSSemanticTokenTypePredefined.DECORATOR
                KaClassKind.OBJECT -> LSSemanticTokenTypePredefined.TYPE
                KaClassKind.COMPANION_OBJECT -> LSSemanticTokenTypePredefined.TYPE
                KaClassKind.ANONYMOUS_OBJECT -> LSSemanticTokenTypePredefined.TYPE
            }

            is KaVariableSymbol -> when (symbol) {
                is KaBackingFieldSymbol -> LSSemanticTokenTypePredefined.PROPERTY
                is KaEnumEntrySymbol -> LSSemanticTokenTypePredefined.ENUM_MEMBER
                is KaJavaFieldSymbol -> LSSemanticTokenTypePredefined.PROPERTY
                is KaLocalVariableSymbol -> LSSemanticTokenTypePredefined.VARIABLE
                is KaValueParameterSymbol -> LSSemanticTokenTypePredefined.PARAMETER
                is KaKotlinPropertySymbol -> LSSemanticTokenTypePredefined.PROPERTY
                is KaSyntheticJavaPropertySymbol -> LSSemanticTokenTypePredefined.PROPERTY
                is KaContextParameterSymbol -> null
                is KaReceiverParameterSymbol -> null
            }

            is KaFunctionSymbol -> when (symbol) {
                is KaNamedFunctionSymbol if symbol.isOperator -> LSSemanticTokenTypePredefined.OPERATOR
                else -> when (symbol.location) {
                    KaSymbolLocation.TOP_LEVEL -> LSSemanticTokenTypePredefined.FUNCTION
                    KaSymbolLocation.CLASS -> LSSemanticTokenTypePredefined.METHOD
                    KaSymbolLocation.PROPERTY -> null // getter/setter
                    KaSymbolLocation.LOCAL -> LSSemanticTokenTypePredefined.FUNCTION
                }
            }

            else -> null
        } ?: return null
        val modifiers = buildList {
            if (symbol is KaVariableSymbol) {
                when {
                    symbol.isVal -> add(LSSemanticTokenModifierPredefined.READONLY)
                    else -> add(LSSemanticTokenModifierPredefined.MODIFICATION)
                }
            }
            if (symbol is KaNamedFunctionSymbol) {
                if (symbol.isSuspend) add(LSSemanticTokenModifierPredefined.ASYNC)
            }

            if (symbol is KaCallableSymbol) {
                if (symbol.location == KaSymbolLocation.TOP_LEVEL || symbol is KaNamedFunctionSymbol && symbol.isStatic) {
                    add(LSSemanticTokenModifierPredefined.STATIC)
                }
            }
            if (symbol is KaDeclarationSymbol) {
                if (symbol.deprecationStatus?.deprecationLevel != null) {
                    add(LSSemanticTokenModifierPredefined.DEPRECATED)
                }
            }
            if (symbol is KaDeclarationSymbol) {
                if (symbol.modality == KaSymbolModality.ABSTRACT) {
                    add(LSSemanticTokenModifierPredefined.ABSTRACT)
                }
            }
            addDefaultLibraryTokenModifier(symbol)
        }
        return LSSemanticToken(type, modifiers)
    }

    private fun MutableList<LSSemanticTokenModifier>.addDefaultLibraryTokenModifier(symbol: KaSymbol) {
        when (symbol) {
            is KaCallableSymbol -> {
                if (symbol.callableId?.packageName?.isFromKotlinStdlib() == true) {
                    add(LSSemanticTokenModifierPredefined.DEFAULT_LIBRARY)
                }
            }

            is KaClassLikeSymbol -> {
                if (symbol.classId?.packageFqName?.isFromKotlinStdlib() == true) {
                    add(LSSemanticTokenModifierPredefined.DEFAULT_LIBRARY)
                }
            }
        }
    }

    private fun FqName.isFromKotlinStdlib(): Boolean {
        return startsWith(StandardNames.BUILT_INS_PACKAGE_NAME)
    }
}



