// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.jetbrains.ls.imports.json.WorkspaceData

internal sealed interface MavenRunResult
internal class SuccessResult(val workspaceData: WorkspaceData) : MavenRunResult
internal class ErrorResult(val e: Throwable) : MavenRunResult

internal fun mergeResults(
    resultDeps: MavenRunResult,
    resultGenSources: MavenRunResult
): MavenRunResult {
    if (resultGenSources is ErrorResult) return resultDeps
    if (resultDeps is ErrorResult) return resultGenSources
    return SuccessResult(
        mergeModels(
            (resultDeps as SuccessResult).workspaceData,
            (resultGenSources as SuccessResult).workspaceData
        )
    )
}

private fun mergeModels(deps: WorkspaceData, genSources: WorkspaceData): WorkspaceData {
    return deps
}
