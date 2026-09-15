// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.openapi.progress.ProgressManager.getInstance
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
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil.afterMovement
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil.moveElements
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil.retargetClassifiedUsages
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil.retargetCodeUsages
import com.intellij.refactoring.util.NonCodeUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.ls.api.core.processors.LSRefactoringProcessor

/**
 * @see com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
 */
open class LSMoveFilesOrDirectoriesProcessor(
    private val project: Project,
    private val elementsToMove: Array<PsiElement>,
    private val targetDirectory: PsiDirectory,
    private val searchForReferences: Boolean,
    private val searchForComments: Boolean,
    private val searchForTextOccurrences: Boolean
) : LSRefactoringProcessor {
    private val classifiedUsages: MutableMap<PsiFile, List<UsageInfo>> = mutableMapOf()

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
        return usages.mapNotNull { it.file } + elementsToMove.flatMap { it.filesToSave() }
    }

    private fun PsiElement.filesToSave(): List<PsiFile> = when (this) {
        is PsiDirectory -> files.asList() + subdirectories.flatMap { it.filesToSave() }
        else -> listOfNotNull(containingFile)
    }

    override fun performRefactoring(
        usages: Array<UsageInfo>,
        transaction: RefactoringTransaction
    ) {
        val codeUsages = usages.filter { it !is NonCodeUsageInfo }
        val listeners: List<RefactoringElementListener> =
            elementsToMove.map { item: PsiElement -> transaction.getElementListener(item) }
        val result = moveElements(
            project, elementsToMove,
            targetDirectory,
            getInstance().getProgressIndicator(),
            searchForReferences
        )
        retargetCodeUsages(codeUsages.toTypedArray<UsageInfo>())
        retargetClassifiedUsages(classifiedUsages, result.oldToNewMap)
        afterMovement(listeners, result.movedElementPointers)
    }

    override fun createEventData(): RefactoringEventData {
        return RefactoringEventData().apply { addElements(elementsToMove) }
    }

    companion object {
        fun create(elementsToMove: Array<PsiElement>, targetDirectory: PsiDirectory): LSRefactoringProcessor {
            val project = targetDirectory.project
            return LSMoveFilesOrDirectoriesProcessor(
                project = project,
                elementsToMove = elementsToMove,
                targetDirectory = targetDirectory,
                searchForReferences = true,
                searchForComments = false,
                searchForTextOccurrences = false
            )
        }
    }
}
