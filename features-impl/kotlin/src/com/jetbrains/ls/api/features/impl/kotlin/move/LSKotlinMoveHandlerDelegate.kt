// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.processors.LSRefactoringProcessor
import com.jetbrains.ls.api.features.impl.common.move.LSMoveHandlerDelegate
import com.jetbrains.ls.api.features.impl.kotlin.processors.LSMoveKotlinFileProcessor
import org.jetbrains.kotlin.idea.base.util.KotlinSingleClassFileAnalyzer
import org.jetbrains.kotlin.idea.k2.refactoring.move.ui.K2MoveModel
import org.jetbrains.kotlin.psi.KtFile

/**
 * @see org.jetbrains.kotlin.idea.k2.refactoring.move.K2MoveHandler
 */
internal class LSKotlinMoveHandlerDelegate : LSMoveHandlerDelegate {
    override fun prepareElementToMove(element: PsiElement): PsiElement? {
        return when (element) {
            is KtFile -> {
                val clazz = KotlinSingleClassFileAnalyzer.getSingleClass(element)

                // Target elements to move are evaluated in `org.jetbrains.kotlin.idea.projectView.KotlinExpandNodeProjectViewProvider.modify`
                if (clazz != null && clazz.containingKtFile.declarations.size == 1) clazz else element
            }
            else -> null
        }
    }

    override fun canMove(sources: Array<PsiElement>, targetDirectory: PsiDirectory): Boolean {
        return org.jetbrains.kotlin.idea.k2.refactoring.move.processor.canMove(sources)
    }

    override fun createProcessor(
        sources: Array<PsiElement>,
        targetDirectory: PsiDirectory
    ): LSRefactoringProcessor? {
        val model = K2MoveModel.create(
            elements = sources,
            targetContainer = targetDirectory,
            editor = null,
            moveCallBack = null,
            canShowUI = false
        ) ?: return null

        return LSMoveKotlinFileProcessor.create(model)
    }
}