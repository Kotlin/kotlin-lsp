// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inspectCode

import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.PartialResultParams
import com.jetbrains.lsp.protocol.ProgressToken
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.WorkDoneProgressParams
import kotlinx.serialization.Serializable

/**
 * Parameters of `intellij/inspectCode`, the request behind Inspect Code and Run Inspection by Name: a batch run of the local
 * inspections over [scope].
 *
 * The server streams one [InspectCodeFileReport] list per `$/progress` with [partialResultToken] (lsp.protocol `streamResults`),
 * and answers with an [InspectCodeSummary] when the run ends. Without a token the reports are not sent at all, so a client always
 * sends one. `$/cancelRequest` stops the run; the reports sent before stay valid.
 */
@Serializable
data class InspectCodeParams(
    val scope: InspectCodeScope,
    /** The short names of the inspections to run; `null` runs every inspection the server enables by default. */
    val inspections: List<String>? = null,
    /** Inspect test sources too, as the "Include test sources" option of the monolith. */
    val includeTests: Boolean = true,
    /** Report compiler errors too; the editor shows them anyway. */
    val includeCompilerErrors: Boolean = false,
    override val workDoneToken: ProgressToken? = null,
    override val partialResultToken: ProgressToken? = null,
) : WorkDoneProgressParams, PartialResultParams

/**
 * The files of an `intellij/inspectCode` run. [uris] are files and directories (a directory covers its content, recursive);
 * `null` covers the whole workspace. The server inspects only the files of its project content.
 */
@Serializable
data class InspectCodeScope(
    val uris: List<URI>? = null,
)

/**
 * The problems of one file of an `intellij/inspectCode` run, one element of a partial result.
 *
 * [version] is the document version the server inspected, `null` for a file the client has not opened. Each
 * [Diagnostic.code] is the short name of its inspection. [newInspections] describes the inspections that appear in the
 * run for the first time, so the client can group the problems without `intellij/inspections`. [processed] of [total]
 * files are done when the server sends the report; the server may skip a file without problems, and still counts it.
 */
@Serializable
data class InspectCodeFileReport(
    val uri: DocumentUri,
    val version: Int? = null,
    val diagnostics: List<Diagnostic>,
    val newInspections: List<InspectionInfo> = emptyList(),
    val processed: Int,
    val total: Int,
)

/**
 * The result of `intellij/inspectCode`: [files] inspected, [problems] reported, [skipped] files (too large, binary, failed).
 * [incompleteReason] says why the run stopped early (for example, the workspace started to reindex); `null` for a full run.
 */
@Serializable
data class InspectCodeSummary(
    val files: Int,
    val problems: Int,
    val skipped: Int = 0,
    val incompleteReason: String? = null,
)

/**
 * One inspection of the server, an element of the `intellij/inspections` result and of
 * [InspectCodeFileReport.newInspections].
 *
 * [id] is the short name ([Diagnostic.code] of its problems); [groupPath] is the group path of the settings tree, outer
 * group first; [language] is the language id, `null` for an inspection of any language. [defaultLevel] is the name of the
 * default highlight severity (for example `WARNING`). [cleanupTool]: Code Cleanup runs its fixes. [disabledInServer]: the
 * server turns it off (an inspection profile patcher), so a run does not report it.
 */
@Serializable
data class InspectionInfo(
    val id: String,
    val displayName: String,
    val groupPath: List<String>,
    val language: String? = null,
    val defaultLevel: String,
    val enabledByDefault: Boolean,
    val cleanupTool: Boolean = false,
    val disabledInServer: Boolean = false,
)
