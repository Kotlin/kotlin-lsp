// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.processors

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import com.intellij.refactoring.util.NonCodeUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.k2.refactoring.move.ui.K2MoveModel

/**
 * @see com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
 * @see org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2MoveFilesOrDirectoriesRefactoringProcessor
 */
internal class LSMoveKotlinFileProcessor(
    private val project: Project,
    private val elementsToMove: Array<PsiElement>,
    private val targetDirectory: PsiDirectory,
    private val searchForReferences: Boolean,
    private val searchForComments: Boolean,
    private val searchForTextOccurrences: Boolean
) : LSRefactoringProcessor {
    private val classifiedUsages : MutableMap<PsiFile, List<UsageInfo>> = mutableMapOf()

    override fun collectConflicts(
        refUsages: Ref<Array<UsageInfo>>,
        conflicts: MultiMap<PsiElement, String>
    ) {
        MoveFileHandler.detectConflicts(elementsToMove, refUsages.get(), targetDirectory, conflicts)
    }

    override fun findUsages(): Array<UsageInfo> {
        val usagesContext = MoveFilesOrDirectoriesUtil.findUsages(
            project,
            elementsToMove,
            targetDirectory,
            searchForReferences,
            searchForComments,
            searchForTextOccurrences
        )
        classifiedUsages.putAll(usagesContext.classifiedUsages)
        return usagesContext.allUsages.toTypedArray()
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> = initialUsages

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> {
        return usages.mapNotNull { it.file } + elementsToMove.mapNotNull { it.containingFile }
    }

    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    override fun performRefactoring(
        usages: Array<UsageInfo>,
        transaction: RefactoringTransaction
    ) {
        allowAnalysisOnEdt {
            allowAnalysisFromWriteAction {
                doPerformRefactoring(usages, transaction)
            }
        }
    }

    private fun doPerformRefactoring(
        usages: Array<UsageInfo>,
        transaction: RefactoringTransaction
    ) {
        val codeUsages = usages.filter { it !is NonCodeUsageInfo }

        val listeners: List<RefactoringElementListener> =
            elementsToMove.map { item: PsiElement -> transaction.getElementListener(item) }

        val result = MoveFilesOrDirectoriesUtil.moveElements(
            project, elementsToMove,
            targetDirectory,
            ProgressManager.getInstance().getProgressIndicator(),
            searchForReferences
        )

        MoveFilesOrDirectoriesUtil.retargetCodeUsages(codeUsages.toTypedArray())
        MoveFilesOrDirectoriesUtil.retargetClassifiedUsages(classifiedUsages, result.oldToNewMap)

        MoveFilesOrDirectoriesUtil.afterMovement(listeners, result.movedElementPointers)
    }


    override fun createEventData(): RefactoringEventData {
        return RefactoringEventData().apply { addElements(elementsToMove) }
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