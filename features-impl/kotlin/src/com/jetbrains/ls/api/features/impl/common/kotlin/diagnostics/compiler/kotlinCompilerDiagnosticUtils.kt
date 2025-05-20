package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.jetbrains.lsp.protocol.DiagnosticSeverity
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity

internal fun KaSeverity.toLsp(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.Error
    KaSeverity.WARNING -> DiagnosticSeverity.Warning
    KaSeverity.INFO -> DiagnosticSeverity.Information
}