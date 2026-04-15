// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.intellij.util.containers.nullize
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SourceRootData
import com.jetbrains.ls.imports.json.WorkspaceData

internal sealed interface MavenRunResult
internal class SuccessResult(val workspaceData: WorkspaceData) : MavenRunResult
internal class ErrorResult(val e: Throwable) : MavenRunResult

internal fun mergeResults(
    resultDeps: MavenRunResult,
    resultGenSources: MavenRunResult
): MavenRunResult {
    val sourcesWD = (resultGenSources as? SuccessResult)?.workspaceData
    val resultWD = (resultDeps as? SuccessResult)?.workspaceData ?: return resultDeps
    return SuccessResult(
        mergeModels(
            resultWD,
            sourcesWD
        )
    )
}

private fun mergeModels(deps: WorkspaceData, genSources: WorkspaceData?): WorkspaceData {
    val sourcesModules = genSources?.modules?.associateBy { it.name } ?: emptyMap()
    return deps.copy(
        modules = deps.modules.map { module ->
            module.copyWithReplacedSources(sourcesModules[module.name])
        }
    )
}

// we need to replace our content roots with content roots received from source data.
// If the source roots have the same path, we should take one with the best rank
// Still, we have to preserve types of our source roots, if they have rank more than received from gen-sources
//we can just copy when mullitype-source roots are supported.
private fun ModuleData.copyWithReplacedSources(sourceData: ModuleData?): ModuleData {
    val allSourceRootsByRank = rankSourceRoots(
        this.contentRoots.flatMap { it.sourceRoots } + (sourceData?.contentRoots?.flatMap { it.sourceRoots } ?: emptyList())
    ).toMutableMap()

    val newContentRoots = sourceData?.contentRoots ?: this.contentRoots
    val contentRootsWithRankedSources = newContentRoots.mapNotNull mapContentRoot@{ cr ->
        val newSourceRoots = cr.sourceRoots.mapNotNull { sr ->
            allSourceRootsByRank.remove(sr.path)
        }
        return@mapContentRoot cr.copy(
            sourceRoots = newSourceRoots
        )
    }

    return this.copy(
        contentRoots = contentRootsWithRankedSources
    )
}


private fun rankSourceRoots(
    roots: List<SourceRootData>
): MutableMap<String, SourceRootData> {
    val typesRank = arrayOf("java-source", "java-test", "java-resource", "java-test-resource")
        .mapIndexed { i, s -> s to i }.toMap()

    val result = HashMap<String, SourceRootData>()
    fun addWithRank(data: SourceRootData) {
        val prev = result[data.path]
        if (prev == null) {
            result[data.path] = data
        } else {
            val previousRank = typesRank[prev.type]
            val newRank = typesRank[data.type] ?: return
            if (previousRank == null || previousRank > newRank) {
                result[data.path] = data
            }
        }
    }
    roots.forEach(::addWithRank)
    return result
}

