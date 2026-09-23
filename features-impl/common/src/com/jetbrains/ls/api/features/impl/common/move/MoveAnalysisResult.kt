// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.jetbrains.ls.api.core.processors.LSRefactoringProcessor
import org.jetbrains.annotations.Nls

/**
 * The move refactoring consists of 2 main steps:
 * 1. Find Appropriate [com.jetbrains.ls.api.core.processors.LSRefactoringProcessor]
 * 2. Execute the refactoring (execution)
 *
 * This interface holds the result of step 1.
 * @see LSCommonMoveProvider
 */
sealed interface MoveAnalysisResult {
    /**
     * Move is possible and can be done via [LSRefactoringProcessor].
     */
    @JvmInline
    value class Success(val processor: LSRefactoringProcessor) : MoveAnalysisResult

    /**
     * There was some error preventing files/directories move.
     */
    @JvmInline
    value class Error(val message: @Nls String) : MoveAnalysisResult
}