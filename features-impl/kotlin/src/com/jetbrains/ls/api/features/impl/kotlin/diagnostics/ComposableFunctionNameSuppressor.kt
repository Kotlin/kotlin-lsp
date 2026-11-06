// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Suppresses the `FunctionName` inspection for `@Composable` functions, which are expected
 * to be named in PascalCase by Compose naming conventions.
 *
 * Mirrors `com.android.tools.compose.ComposeSuppressor` from the Android plugin, which is not
 * part of the language server distribution.
 */
internal class ComposableFunctionNameSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId != "FunctionName") return false
        val function = element as? KtNamedFunction
            ?: element.parent as? KtNamedFunction
            ?: return false
        // Matching by short name only: suppression runs on every reported problem, so it must
        // stay cheap and cannot resolve the annotation to its fully qualified name.
        return function.annotationEntries.any { it.shortName?.asString() == COMPOSABLE_SHORT_NAME }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY

    private companion object {
        private const val COMPOSABLE_SHORT_NAME = "Composable"
    }
}
