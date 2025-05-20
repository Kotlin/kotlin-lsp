package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticData
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticSource
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi

@Serializable
internal data class KotlinCompilerDiagnosticData(
    val severityName: String,
    val diagnosticClass: String,
    val psi: PsiSerializablePointer,
) : DiagnosticData {
    override val diagnosticSource: DiagnosticSource = source

    fun matches(kaDiagnostic: KaDiagnosticWithPsi<*>): Boolean {
        if (kaDiagnostic::class.java.name != diagnosticClass) return false
        if (kaDiagnostic.severity.name != severityName) return false
        if (!psi.matches(kaDiagnostic.psi)) return false
        return true
    }

    companion object {
        val source: DiagnosticSource = DiagnosticSource("kotlinCompilerDiagnostic")

        context(LSAnalysisContext, LSServer)
        fun create(diagnostic: KaDiagnosticWithPsi<*>, file: VirtualFile): KotlinCompilerDiagnosticData {
            val psi = PsiSerializablePointer.create(diagnostic.psi, file)
            return KotlinCompilerDiagnosticData(
                severityName = diagnostic.severity.name,
                diagnosticClass = diagnostic::class.java.name,
                psi = psi,
            )
        }
    }
}