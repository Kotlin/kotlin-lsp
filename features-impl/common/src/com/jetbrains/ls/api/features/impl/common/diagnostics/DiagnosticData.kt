// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

interface DiagnosticData {
    val diagnosticSource: DiagnosticSource
}

@Serializable
data class DiagnosticSource(
    val name: String,
)

inline fun <reified D : DiagnosticData> Diagnostic.diagnosticData(): D? {
    val data = data ?: return null
    return runCatching {
        LSP.json.decodeFromJsonElement<D>(data)
    }.getOrNull()
}

inline fun <reified D : DiagnosticData> CodeActionParams.diagnosticData(): List<DiagnosticWithData<D>> {
    return context.diagnostics.mapNotNull { diagnostic ->
        val data = diagnostic.diagnosticData<D>() ?: return@mapNotNull null
        DiagnosticWithData(diagnostic, data)
    }
}

class DiagnosticWithData<D : DiagnosticData>(
    val diagnostic: Diagnostic,
    val data: D,
)