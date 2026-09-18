// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.ide.util.PackageUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.processors.LSMoveDirectoryProcessor
import com.jetbrains.ls.api.core.processors.MoveDirectoryContext
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.impl.common.move.LSMoveHandlerDelegate
import com.jetbrains.ls.api.features.impl.common.move.MoveAnalysisResult

internal class LSJvmDirectoryMoveHandlerDelegate : LSMoveHandlerDelegate {
    override val isOnlyDirectories: Boolean
        get() = true

    override fun prepareElementToMove(element: PsiElement): PsiElement? = null

    override fun canMove(
        sources: Array<PsiElement>,
        targetDirectory: PsiDirectory
    ): Boolean {
        return sources.all { it is PsiDirectory && PackageUtil.isDirectoryUnderPackage(it) }
    }

    override fun createProcessor(
        sources: Array<PsiElement>,
        targetDirectory: PsiDirectory
    ): MoveAnalysisResult {
        val directories = sources.map { it as PsiDirectory }.toTypedArray()
        val context = MoveDirectoryContext(targetDirectory, directories)
        val processor = LSMoveDirectoryProcessor.create(context)
            ?: return MoveAnalysisResult.Error(LspServerBundle.message("error.move.unable.to.update.moved.files"))
        return MoveAnalysisResult.Success(processor)
    }
}
