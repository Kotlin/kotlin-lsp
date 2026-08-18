// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.processors

import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringProcessor

/**
 * @see com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
 */
internal class MoveKotlinFileProcessor : RefactoringProcessor {
    override fun collectConflicts(
        refUsages: Ref<Array<UsageInfo>>,
        conflicts: MultiMap<PsiElement, String>
    ) {
        //
        MoveFileHandler.detectConflicts(emptyArray(), refUsages.get(), null, conflicts)
    }

    override fun findUsages(): Array<UsageInfo>? {
        TODO("not implemented")
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> {
        TODO("not implemented")
    }

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> {
        TODO("not implemented")
    }

    override fun performRefactoring(
        usages: Array<UsageInfo>,
        transaction: RefactoringTransaction
    ) {
        TODO("not implemented")
    }


    override fun createEventData(): RefactoringEventData {
        TODO("not implemented")
    }

    companion object {
        fun create(): RefactoringProcessor? {
            return null
        }
    }
}
