// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inspectCode

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.URI

/**
 * Runs the inspections of its languages over one file in batch mode, for `intellij/inspectCode` (Inspect Code, Run
 * Inspection by Name), and describes them for `intellij/inspections`.
 *
 * Batch mode is the mode of the monolith's Inspect Code: `onTheFly = false`, and no fixes are registered for the problems.
 * A client gets the fixes of a batch problem from `textDocument/codeAction`, which runs its inspection on the file again.
 */
interface LSBatchInspectionProvider : LSLanguageSpecificConfigurationEntry {
    /**
     * The problems of the file at [uri], in an analysis context of its own. [inspectionIds] are the inspections to run
     * (a diagnostic code or a short name); `null` runs the ones the server enables by default.
     */
    context(server: LSServer)
    suspend fun inspectFile(uri: URI, inspectionIds: Set<String>?): LSBatchFileResult

    /** The inspections this provider runs, for every language it supports; the extensions are read in an analysis context. */
    context(server: LSServer)
    suspend fun inspections(): List<LSBatchInspection>
}

/** The problems of one file, or [skipped] when the provider could not inspect it (too large, no PSI, filtered out). */
class LSBatchFileResult(
    val diagnostics: List<Diagnostic>,
    val skipped: Boolean = false,
) {
    companion object {
        val SKIPPED: LSBatchFileResult = LSBatchFileResult(emptyList(), skipped = true)
    }
}

/**
 * One inspection of an [LSBatchInspectionProvider]: its [info], the other names that select it ([aliases]), and the
 * [languages] of the files it runs on, so a run by name visits only them.
 */
class LSBatchInspection(
    val info: InspectionInfo,
    val languages: Set<LSLanguage>,
    val aliases: Set<String> = emptySet(),
)
