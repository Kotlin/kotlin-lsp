// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.semanticTokens

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticToken
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenModifier
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenModifierCustom
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenModifierPredefined
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenType
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenTypePredefined
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenWithRange
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokensProviderBase
import com.jetbrains.ls.api.features.utils.allNonWhitespaceChildren
import com.jetbrains.lsp.protocol.Range
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaBackingFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaContextParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgumentName

private val LOG = logger<LSKotlinSemanticTokensProvider>()

/**
 * Marks a `parameter` token on a named-argument name (`foo(name = 1)`).
 * Clients that do not know it color the name as a parameter; Draft drops such tokens to keep its native named-argument color.
 * Keep in sync with `NAMED_ARGUMENT_TOKEN_MODIFIER` in Draft's `DraftIntelliJServerLspIntegrationProvider.kt`.
 */
private val NAMED_ARGUMENT: LSSemanticTokenModifierCustom = LSSemanticTokenModifierCustom("namedArgument")

@ApiStatus.Internal
object LSKotlinSemanticTokensProvider : LSSemanticTokensProviderBase() {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val supportedTokenTypes: List<LSSemanticTokenType> = LSSemanticTokenTypePredefined.ALL
    override val supportedTokenModifiers: List<LSSemanticTokenModifier> = LSSemanticTokenModifierPredefined.ALL + NAMED_ARGUMENT

    context(server: LSServer)
    override fun getSemanticTokens(
        psiFile: PsiFile,
        document: Document,
        documentRange: Range?
    ): List<LSSemanticTokenWithRange> {
        if (psiFile !is KtFile) return emptyList()
        return analyze(psiFile) {
            val leafs = psiFile.allNonWhitespaceChildren(document, documentRange)
            if (leafs.isEmpty()) return emptyList()
            leafs.mapNotNull { it.getRangeWithToken(document) }
        }
    }

    context(server: LSServer, kaSession: KaSession)
    private fun PsiElement.getRangeWithToken(document: Document): LSSemanticTokenWithRange? = try {
        val psiElement = this
        with(kaSession) {
            when (psiElement) {
                // label declarations and references (`lit@`, `return@lit`, `this@Outer`), no resolve needed
                is KtLabelReferenceExpression -> {
                    LSSemanticTokenWithRange(LSSemanticToken(LSSemanticTokenTypePredefined.LABEL), textRange.toLspRange(document))
                }

                // a named-argument name (`foo(name = 1)`) always names a parameter, no resolve needed
                is KtSimpleNameExpression if psiElement.parent is KtValueArgumentName -> {
                    LSSemanticTokenWithRange(
                        LSSemanticToken(LSSemanticTokenTypePredefined.PARAMETER, listOf(NAMED_ARGUMENT)),
                        textRange.toLspRange(document),
                    )
                }

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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
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

            // a constructor call is colored as its class, like IDEA; via a typealias the container is the alias
            is KaConstructorSymbol -> {
                val classToken = (symbol.containingDeclaration as? KaClassLikeSymbol)?.let { getRangeWithToken(it) } ?: return null
                // a deprecated constructor of a live class keeps its own modifier
                return if (symbol.isDeprecated && LSSemanticTokenModifierPredefined.DEPRECATED !in classToken.modifiers) {
                    classToken.withModifiers(LSSemanticTokenModifierPredefined.DEPRECATED)
                }
                else classToken
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
                if (symbol.isDeprecated) {
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



