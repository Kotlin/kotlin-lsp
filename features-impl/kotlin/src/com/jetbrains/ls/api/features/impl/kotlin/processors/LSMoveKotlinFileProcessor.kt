// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.processors

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.usageView.UsageInfo
import com.jetbrains.ls.api.features.impl.common.move.LSMoveFilesOrDirectoriesProcessor
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.k2.refactoring.move.ui.K2MoveModel

/**
 * @see org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2MoveFilesOrDirectoriesRefactoringProcessor
 */
internal class LSMoveKotlinFileProcessor(
    project: Project,
    elementsToMove: Array<PsiElement>,
    targetDirectory: PsiDirectory,
    searchForReferences: Boolean,
    searchForComments: Boolean,
    searchForTextOccurrences: Boolean
) : LSMoveFilesOrDirectoriesProcessor(project, elementsToMove, targetDirectory, searchForReferences, searchForComments, searchForTextOccurrences) {
    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    override fun performRefactoring(
        usages: Array<UsageInfo>,
        transaction: RefactoringTransaction
    ) {
        allowAnalysisOnEdt {
            allowAnalysisFromWriteAction {
                super.performRefactoring(usages, transaction)
            }
        }
    }

    companion object {
        fun create(model: K2MoveModel): LSRefactoringProcessor {
            val descriptor = model.toDescriptor()
            return LSMoveKotlinFileProcessor(
                project = descriptor.project,
                elementsToMove = descriptor.sourceElements.toTypedArray(),
                targetDirectory = descriptor.moveDescriptors.first().target.getOrCreateTarget(descriptor.dirStructureMatchesPkg) as PsiDirectory,
                searchForReferences = descriptor.searchReferences,
                searchForComments = descriptor.searchInComments,
                searchForTextOccurrences = descriptor.searchForText
            )
        }
    }
}