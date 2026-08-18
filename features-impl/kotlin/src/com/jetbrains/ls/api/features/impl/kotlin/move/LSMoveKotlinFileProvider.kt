// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.features.impl.common.move.LSMoveFileProviderBase
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringProcessor
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.processors.MoveKotlinFileProcessor

internal object LSMoveKotlinFileProvider : LSMoveFileProviderBase(setOf(LSKotlinLanguage)) {
    context(_: LSAnalysisContext)
    override fun createProcessor(
        targetDirectory: PsiDirectory,
        file: PsiFile
    ): RefactoringProcessor? {
        return MoveKotlinFileProcessor.create()
    }
}
