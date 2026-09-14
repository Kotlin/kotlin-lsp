// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor

/**
 * @see com.intellij.refactoring.move.MoveHandlerDelegate
 */
interface LSMoveHandlerDelegate {
    /**
     * `true` means that it is only able to process directories
     */
    val isOnlyDirectories: Boolean
        get() = false

    /**
     * Extracts more precise target to move from [element]. For example. if [element] is a file that contains a single class.
     * @see com.intellij.ide.projectView.TreeStructureProvider.modify
     */
    fun prepareElementToMove(element: PsiElement): PsiElement?

    /**
     * Checks whether the [sources] can be moved into the [targetDirectory]
     * @see com.intellij.refactoring.move.MoveHandlerDelegate.canMove
     */
    fun canMove(sources: Array<PsiElement>, targetDirectory: PsiDirectory): Boolean

    /**
     * Creates [LSRefactoringProcessor] that will perform move refactoring for [sources] and [targetDirectory]
     */
    fun createProcessor(sources: Array<PsiElement>, targetDirectory: PsiDirectory): LSRefactoringProcessor?

    companion object {
        private val EP_NAME: ExtensionPointName<LSMoveHandlerDelegate> = ExtensionPointName.create("ls.moveHandlerDelegate")

        fun forFiles(): List<LSMoveHandlerDelegate> = EP_NAME.extensionList.filterNot(LSMoveHandlerDelegate::isOnlyDirectories)

        fun forDirectories(): List<LSMoveHandlerDelegate> = EP_NAME.extensionList.filter(LSMoveHandlerDelegate::isOnlyDirectories)
    }
}