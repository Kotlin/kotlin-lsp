package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizerPsiBase
import com.jetbrains.lsp.protocol.SymbolKind
import org.jetbrains.kotlin.idea.presentation.KotlinFunctionPresentation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtTypeReference

class LSKotlinDocumentSymbolCustomizer : LSDocumentSymbolCustomizerPsiBase() {

    override fun getName(element: PsiElement): String? =
        when (element) {
            is KtClassInitializer -> "<class initializer>"
            is KtPropertyAccessor ->
                when {
                    element.isGetter -> "get"
                    else -> "set"
                }
            else -> super.getName(element)
        }

    override fun getKind(element: PsiElement): SymbolKind? =
        element.getKind()

    /**
     * The Kotlin structure view text after the name, from PSI only: `(Int, vararg String): List<String>` for a function (a receiver
     * gives `String.(Int): Unit`), `(Int)` for a constructor, `: Int` for a property (a receiver gives `String.: Int`). IDEA shows
     * the receiver before the name (`String.foo(Int): Unit`), so a client puts the text up to the `.` in front of the name. The K2
     * structure view renders resolved symbols; here an implicit type is left out (an expression body or a property without a
     * type), and a function with a block body or no body and no return type shows `Unit`.
     */
    override fun getDetail(element: PsiElement): String? =
        when (element) {
            is KtFunction -> buildString {
                append(KotlinFunctionPresentation(element, name = "").presentableText)
                if (element !is KtConstructor<*>) {
                    val returnType = element.typeReference?.presentableText() ?: "Unit".takeIf { !element.hasBody() || element.hasBlockBody() }
                    if (returnType != null) append(": ").append(returnType)
                }
            }
            is KtProperty -> {
                val receiver = element.receiverTypeReference?.let {
                    // a function type keeps its parens, as in KotlinFunctionPresentation: `((Int) -> Unit).: Int`
                    if (it.typeElement is KtFunctionType) "(${it.presentableText()})." else "${it.presentableText()}."
                }.orEmpty()
                (receiver + element.typeReference?.let { ": ${it.presentableText()}" }.orEmpty()).takeIf { it.isNotEmpty() }
            }
            is KtParameter -> element.typeReference?.let { ": ${it.presentableText()}" }
            else -> null
        }

    /**
     * Short type text as in the parameters of [KotlinFunctionPresentation]: `kotlin.collections.List<String>` gives `List<String>`.
     * `suspend` is a modifier of the type reference, outside its type element, so it is put back in front.
     */
    private fun KtTypeReference.presentableText(): String {
        val text = (typeElement?.text ?: text).replace(WHITESPACE, " ")
        val splitAt = text.indexOfAny(charArrayOf('<', '(', '?', ' ', '&'))
        val short = if (splitAt < 0) StringUtil.getShortName(text) else StringUtil.getShortName(text.substring(0, splitAt)) + text.substring(splitAt)
        return if (hasModifier(KtTokens.SUSPEND_KEYWORD)) "suspend $short" else short
    }

    override fun isDeprecated(element: PsiElement): Boolean =
        // TODO: Something more robust
        (element as? KtNamedDeclaration)?.annotationEntries?.any { it.shortName.toString() == "Deprecated" } == true

    override fun getNestedDeclarations(element: PsiElement): List<PsiElement> =
        when (element) {
            is KtClassOrObject ->
                element.primaryConstructorParameters.filter { it.valOrVarKeyword != null } +
                    listOfNotNull(element.primaryConstructor) +
                    element.declarations
            is KtDeclarationContainer -> element.declarations
            is KtProperty -> element.accessors
            else -> emptyList()
        }
}

private val WHITESPACE = Regex("\\s+")
