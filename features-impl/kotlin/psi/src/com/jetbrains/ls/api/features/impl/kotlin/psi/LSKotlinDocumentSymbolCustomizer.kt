package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizer
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizerPsiBase
import com.jetbrains.ls.api.core.features.LSDocumentSymbolOrigin
import com.jetbrains.ls.api.core.util.getLspLocation
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
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Local classes, objects (object literals too) and named functions are children of the function, initializer, property or accessor
 * that holds them, as in the K2 structure view (`KotlinStructureViewUtil.getStructureViewChildren`).
 */
class LSKotlinDocumentSymbolCustomizer : LSDocumentSymbolCustomizerPsiBase() {

    /** An object literal is `object` plus its super types, as in the structure view: `object : Runnable`. */
    override fun getName(element: PsiElement): String? =
        when {
            element is KtObjectDeclaration && element.isObjectLiteral() -> "object" + element.getSuperTypeList()?.text?.let { " : $it" }.orEmpty()
            element is KtClassInitializer -> "<class initializer>"
            element is KtPropertyAccessor ->
                when {
                    element.isGetter -> "get"
                    else -> "set"
                }
            else -> super.getName(element)
        }

    /** An object literal has no name identifier: the selection is its `object` keyword. */
    override fun getRanges(element: PsiElement): LSDocumentSymbolCustomizer.ElementRanges? {
        if (element !is KtObjectDeclaration || !element.isObjectLiteral()) return super.getRanges(element)
        val range = element.getLspLocation()?.range ?: return null
        val selectionRange = element.getObjectKeyword()?.getLspLocation()?.range ?: range
        return LSDocumentSymbolCustomizer.ElementRanges(range, selectionRange)
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

    override fun getOrigin(element: PsiElement): String? =
        when {
            element is KtObjectDeclaration && element.isObjectLiteral() -> LSDocumentSymbolOrigin.ANONYMOUS
            else -> null
        }

    /**
     * A class lists its members; a file or script its declarations. A function (constructors too), an initializer, a property or an
     * accessor lists its [localDeclarations]; a property also lists its accessors, in source order.
     */
    override fun getNestedDeclarations(element: PsiElement): List<PsiElement> =
        when (element) {
            is KtClassOrObject ->
                element.primaryConstructorParameters.filter { it.valOrVarKeyword != null } +
                    listOfNotNull(element.primaryConstructor) +
                    element.declarations
            is KtDeclarationContainer -> element.declarations
            is KtFunction, is KtClassInitializer, is KtProperty, is KtPropertyAccessor -> element.localDeclarations()
            else -> emptyList()
        }

    /**
     * The classes, objects and named functions under this element, as the structure view collects them: the walk goes through
     * lambdas, blocks and local properties but not into what it found, so a member of a local class is a child of that class. An
     * accessor is found too (only a property has one as a child), and its body belongs to it.
     */
    private fun PsiElement.localDeclarations(): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                result.add(classOrObject)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                result.add(function)
            }

            override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
                result.add(accessor)
            }
        })
        return result
    }
}

private val WHITESPACE = Regex("\\s+")
