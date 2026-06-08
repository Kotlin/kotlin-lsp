// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.move.moveClassesOrPackages.MoveDirectoryWithClassesProcessor
import com.intellij.refactoring.move.moveClassesOrPackages.MoveDirectoryWithClassesProcessor.TargetDirectoryWrapper
import com.intellij.refactoring.move.moveClassesOrPackages.MoveDirectoryWithClassesProcessorUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * @see MoveDirectoryWithClassesProcessor
 */
class DirectoryMover internal constructor(
    private val project: Project,
    directories: Array<PsiDirectory>,
    private val targetDirectory: PsiDirectory?,
    private val searchInComments: Boolean,
    private val searchInNonJavaFiles: Boolean,
    private val targetDirectoryProvider: (PsiDirectory) -> TargetDirectoryWrapper,
) : RefactoringProcessor {
    private val manager: PsiManager = PsiManager.getInstance(project)
    private val directories: Array<PsiDirectory> = MoveDirectoryWithClassesProcessorUtil.preprocessDirectories(directories, targetDirectory)
    private val filesToMove: MutableMap<VirtualFile, TargetDirectoryWrapper> = HashMap()
    private val nestedDirsToMove: MutableMap<PsiDirectory, TargetDirectoryWrapper> = LinkedHashMap()

    init {
        directories.forEach { dir ->
            MoveDirectoryWithClassesProcessorUtil.collectFiles2Move(filesToMove, nestedDirsToMove, dir, dir, targetDirectoryProvider(dir))
        }
    }

    override fun findUsages(): Array<UsageInfo> {
        return MoveDirectoryWithClassesProcessorUtil.findUsages(project, filesToMove, directories, searchInComments, searchInNonJavaFiles)
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> {
        return initialUsages
    }

    override fun collectConflicts(refUsages: Ref<Array<UsageInfo>>, conflicts: MultiMap<PsiElement, String>) {
        MoveDirectoryWithClassesProcessorUtil.collectConflicts(conflicts, refUsages, filesToMove, project, targetDirectory)
    }

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> {
        return usages.mapNotNull { it.file } + filesToMove.keys.mapNotNull { manager.findFile(it) }
    }

    override fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction) {
        MoveDirectoryWithClassesProcessorUtil.doPerformRefactoring(project, usages, directories, nestedDirsToMove, filesToMove,
            transaction, targetDirectoryProvider)
    }

    override fun createEventData(): RefactoringEventData {
        val data = RefactoringEventData()
        data.addElements(directories)
        return data
    }

    companion object {
        /**
         * Validates [context] and constructs a [DirectoryMover] under a read action.
         *
         * Returns `null` if the source list is empty or any directory is no longer valid.
         */
        suspend fun create(context: RenameSingleDirectoryContext): DirectoryMover? = readAction {
            if (!context.directory.isValid) return@readAction null

            DirectoryMover(
                project = context.directory.project,
                directories = arrayOf(context.directory),
                targetDirectory = null,
                searchInComments = false,
                searchInNonJavaFiles = false,
                targetDirectoryProvider = { dir -> TargetDirectoryWrapper(dir.parentDirectory, context.newName) }
            )
        }
    }
}

/**
 * Data needed to perform the move to sibling (rename) of single directory operation in the language server.
 * @param directory directory to be moved
 * @param newName new name of the directory
 */
class RenameSingleDirectoryContext(
    val directory: PsiDirectory,
    val newName: String
): RefactoringContext
