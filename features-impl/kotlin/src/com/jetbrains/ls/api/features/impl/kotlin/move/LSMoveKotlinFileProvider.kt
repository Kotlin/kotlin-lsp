// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.features.impl.common.move.LSMoveFileProviderBase
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.processors.LSMoveKotlinFileProcessor
import org.jetbrains.kotlin.idea.base.util.KotlinSingleClassFileAnalyzer
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.canMove
import org.jetbrains.kotlin.idea.k2.refactoring.move.ui.K2MoveModel
import org.jetbrains.kotlin.psi.KtFile


/**
 * @see org.jetbrains.kotlin.idea.k2.refactoring.move.K2MoveHandler
 */
internal object LSMoveKotlinFileProvider : LSMoveFileProviderBase(setOf(LSKotlinLanguage)) {
    context(_: LSAnalysisContext)
    override fun createProcessor(
        targetDirectory: PsiDirectory,
        file: PsiFile
    ): LSRefactoringProcessor? {
        if (file !is KtFile) return null
        val clazz = KotlinSingleClassFileAnalyzer.getSingleClass(file)

        // Target elements to move are evaluated in `org.jetbrains.kotlin.idea.projectView.KotlinExpandNodeProjectViewProvider.modify`
        val targets: Array<PsiElement> = if (clazz != null && clazz.containingKtFile.declarations.size == 1) arrayOf(clazz) else arrayOf(file)
        if (!canMove(targets)) return null

        val model = K2MoveModel.create(
            elements = targets,
            targetContainer = targetDirectory,
            editor = null,
            moveCallBack = null,
            canShowUI = false
        ) ?: return null

        return LSMoveKotlinFileProcessor.create(model)
    }
}