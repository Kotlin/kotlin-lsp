// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.processors.LSRefactoringProcessor

/**
 * Fallback handler for moving files and directories. It is used, for example, when both java and kotlin files are moved within request
 *
 * @see com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesHandler
 */
internal object LSGenericMoveHandlerDelegate : LSMoveHandlerDelegate {
    override fun prepareElementToMove(element: PsiElement): PsiElement? = null

    override fun canMove(sources: Array<PsiElement>, targetDirectory: PsiDirectory): Boolean = true

    override fun createProcessor(
        sources: Array<PsiElement>,
        targetDirectory: PsiDirectory
    ): LSRefactoringProcessor {
        val adjustedElements = sources.map { element ->
            val file = element.containingFile
            file ?: element
        }.toTypedArray()

        return LSMoveFilesOrDirectoriesProcessor.create(elementsToMove = adjustedElements, targetDirectory = targetDirectory)
    }
}