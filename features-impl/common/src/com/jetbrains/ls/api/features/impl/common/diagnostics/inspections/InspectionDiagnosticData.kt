// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticData
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticSource
import kotlinx.serialization.Serializable

@Serializable
internal data class InspectionDiagnosticData(
    val fixes: List<InspectionQuickfixData>,
) : DiagnosticData {
    override val diagnosticSource: DiagnosticSource = source

    companion object {
        val source: DiagnosticSource = DiagnosticSource("inspection")
    }
}

