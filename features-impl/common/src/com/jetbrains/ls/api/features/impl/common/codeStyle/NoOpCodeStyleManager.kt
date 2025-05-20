package com.jetbrains.ls.api.features.impl.common.codeStyle

import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.Indent
import com.intellij.util.ThrowableRunnable

/**
 * LSP does not care about formatting for now
 */
internal class NoOpCodeStyleManager(private val project: Project) : CodeStyleManager() {
    override fun getProject(): Project = project

    override fun reformat(element: PsiElement): PsiElement = element

    override fun reformat(element: PsiElement, canChangeWhiteSpacesOnly: Boolean): PsiElement = element

    override fun reformatRange(element: PsiElement, startOffset: Int, endOffset: Int): PsiElement? = element

    override fun reformatRange(
        element: PsiElement,
        startOffset: Int,
        endOffset: Int,
        canChangeWhiteSpacesOnly: Boolean,
    ): PsiElement? = element

    override fun reformatText(file: PsiFile, startOffset: Int, endOffset: Int) {}

    override fun reformatText(file: PsiFile, ranges: Collection<TextRange?>) {}

    override fun adjustLineIndent(file: PsiFile, rangeToAdjust: TextRange?) {}

    override fun adjustLineIndent(file: PsiFile, offset: Int): Int = offset

    override fun adjustLineIndent(document: Document, offset: Int): Int = offset

    override fun isLineToBeIndented(file: PsiFile, offset: Int): Boolean = false

    override fun getLineIndent(file: PsiFile, offset: Int): String? = null

    override fun getLineIndent(document: Document, offset: Int): String? = null

    override fun getIndent(text: String?, fileType: FileType?): Indent? = null

    override fun fillIndent(indent: Indent?, fileType: FileType?): String? = null

    override fun zeroIndent(): Indent? = null

    override fun reformatNewlyAddedElement(block: ASTNode, addedElement: ASTNode) {}

    override fun isSequentialProcessingAllowed(): Boolean = false

    override fun performActionWithFormatterDisabled(r: Runnable?) {
        r?.run()
    }

    override fun <T : Throwable?> performActionWithFormatterDisabled(r: ThrowableRunnable<T?>?) {
        r?.run()
    }

    override fun <T : Any?> performActionWithFormatterDisabled(r: Computable<T?>?): T? = r?.compute()
}