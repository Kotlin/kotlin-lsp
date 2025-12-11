// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import com.jetbrains.lsp.protocol.DiagnosticTag

// TODO LSP-241 design, currently some random conversions
/**
 * Converts highlighting type from inspections to [DiagnosticSeverity]
 */
fun ProblemHighlightType.toLspSeverity(): DiagnosticSeverity = when (this) {
    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> DiagnosticSeverity.Error
    ProblemHighlightType.LIKE_DEPRECATED -> DiagnosticSeverity.Warning
    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> DiagnosticSeverity.Warning
    ProblemHighlightType.ERROR -> DiagnosticSeverity.Error
    ProblemHighlightType.WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.GENERIC_ERROR -> DiagnosticSeverity.Error
    ProblemHighlightType.INFO -> DiagnosticSeverity.Information
    ProblemHighlightType.WEAK_WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.INFORMATION -> DiagnosticSeverity.Hint
    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> DiagnosticSeverity.Information
    ProblemHighlightType.POSSIBLE_PROBLEM -> DiagnosticSeverity.Warning
}

/**
 * Converts highlighting type from annotators and visitors to [DiagnosticSeverity]
 */
fun HighlightSeverity.toLspSeverity(): DiagnosticSeverity = when (this) {
    HighlightSeverity.ERROR -> DiagnosticSeverity.Error
    HighlightSeverity.WARNING -> DiagnosticSeverity.Warning
    HighlightSeverity.INFORMATION -> DiagnosticSeverity.Hint
    else -> throw UnsupportedOperationException("Unsupported severity: $this")
}

/**
 * Converts highlighting type from inspections to [DiagnosticTag]
 */
fun ProblemHighlightType.toLspTags(): List<DiagnosticTag>? = when (this) {
    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> null
    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> null
    ProblemHighlightType.LIKE_DEPRECATED -> listOf(DiagnosticTag.Deprecated)
    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> listOf(DiagnosticTag.Unnecessary)
    ProblemHighlightType.ERROR -> null
    ProblemHighlightType.WARNING -> null
    ProblemHighlightType.GENERIC_ERROR -> null
    ProblemHighlightType.INFO -> null
    ProblemHighlightType.WEAK_WARNING -> null
    ProblemHighlightType.INFORMATION -> null
    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> listOf(DiagnosticTag.Deprecated)
    ProblemHighlightType.POSSIBLE_PROBLEM -> null
}

/**
 * Converts highlighting type from annotators and visitors to [DiagnosticTag]
 */
fun HighlightSeverity.toLspTags(): List<DiagnosticTag> = when (this) {
    HighlightSeverity.WARNING -> listOf(DiagnosticTag.Unnecessary)
    HighlightSeverity.INFORMATION -> emptyList()
    else -> throw UnsupportedOperationException("Unsupported severity: $this")
}