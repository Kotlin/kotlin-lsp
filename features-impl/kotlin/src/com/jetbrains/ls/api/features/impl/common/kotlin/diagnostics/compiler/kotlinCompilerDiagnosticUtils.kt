// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.jetbrains.lsp.protocol.DiagnosticSeverity
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity

internal fun KaSeverity.toLsp(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.Error
    KaSeverity.WARNING -> DiagnosticSeverity.Warning
    KaSeverity.INFO -> DiagnosticSeverity.Information
}