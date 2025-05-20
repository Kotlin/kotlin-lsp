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

