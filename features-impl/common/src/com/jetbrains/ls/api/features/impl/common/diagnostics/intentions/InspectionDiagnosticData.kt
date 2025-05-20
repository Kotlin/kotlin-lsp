//package com.jetbrains.ls.api.features.impl.common.diagnostics.intentions
//
//import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticData
//import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticSource
//import kotlinx.serialization.Serializable
//
//@Serializable
//internal data class IntentionDiagnosticData(
//    val fixes: List<IntentionQuickfixData>,
//) : DiagnosticData {
//    override val diagnosticSource: DiagnosticSource = source
//
//    companion object {
//        val source: DiagnosticSource = DiagnosticSource("intention")
//    }
//}
//
