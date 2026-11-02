// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.features.impl.common.modcommands.DocumentStamps
import com.jetbrains.ls.api.features.impl.common.modcommands.ModCommandFix
import com.jetbrains.ls.api.features.impl.common.modcommands.stamps
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

//region Data classes

interface DiagnosticData {
    val diagnosticSource: DiagnosticSource
}

@Serializable
data class DiagnosticSource(
    val name: String,
)

/**
 * A basic diagnostic data that may have ModCommand-based quick-fixes.
 */
@Serializable
data class SimpleDiagnosticData(
    override val diagnosticSource: DiagnosticSource,
    val fixes: List<SimpleDiagnosticQuickfixData>,
) : DiagnosticData

/**
 * A quick fix of a diagnostic, computed when the diagnostic was computed.
 *
 * [stamps] are the stamps of the text the fix was computed on, taken in the same read action as [modCommandData].
 * The code action of the fix carries them to the execution, which refuses the fix when the text changed since the
 * diagnostic. They are empty for a [ModCommandData.LazyAction], which computes its edits only at the execution, and
 * they are omitted from the JSON then, so a lazy fix encodes as before.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SimpleDiagnosticQuickfixData(
    val name: String,
    val modCommandData: ModCommandData,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stamps: DocumentStamps = emptyMap(),
)

/**
 * [this] fix of a diagnostic in the file of [diagnosedFileUrl] (an IntelliJ file url), stamped with the text it was
 * computed on. Must run in the read action that computed the fix.
 */
context(_: LSAnalysisContext)
fun ModCommandFix.toDiagnosticQuickfixData(diagnosedFileUrl: String?): SimpleDiagnosticQuickfixData =
    SimpleDiagnosticQuickfixData(name = name, modCommandData = data, stamps = data.stamps(diagnosedFileUrl))

/**
 * The stamps the code action of [this] fix carries to the execution: the [stamps][SimpleDiagnosticQuickfixData.stamps]
 * taken with the fix. An eager fix without them (data of an older server, or of a producer that does not stamp) is
 * stamped now, with the text of [fileUrl] at the listing. Must run in a read action.
 */
context(_: LSAnalysisContext)
fun SimpleDiagnosticQuickfixData.listingStamps(fileUrl: String?): DocumentStamps =
    stamps.ifEmpty { modCommandData.stamps(fileUrl) }

//endregion

//region Helper functions to extract diagnostic data from the request

/**
 * Convenient data holder storing a [Diagnostic] together with its `data` already deserialized to the correct [DiagnosticData] subtype.
 */
class DiagnosticWithData<D : DiagnosticData>(
    val diagnostic: Diagnostic,
    val data: D,
)

inline fun <reified D : DiagnosticData> CodeActionParams.diagnosticData(): List<DiagnosticWithData<D>> {
    return context.diagnostics.mapNotNull { diagnostic ->
        val data = diagnostic.diagnosticData<D>() ?: return@mapNotNull null
        DiagnosticWithData(diagnostic, data)
    }
}

inline fun <reified D : DiagnosticData> Diagnostic.diagnosticData(): D? {
    val data = data ?: return null
    return runCatching {
        LSP.json.decodeFromJsonElement<D>(data)
    }.getOrNull()
}
