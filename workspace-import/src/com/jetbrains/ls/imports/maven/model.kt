// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.jetbrains.ls.imports.json.ContentRootData
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
    val depsContentRoots = this.contentRoots
    val genContentRoots = sourceData?.contentRoots
    val baseContentRoots = genContentRoots ?: depsContentRoots

    val allSourceRootsByRank = rankSourceRoots(
        depsContentRoots.flatMap { it.sourceRoots } + (genContentRoots?.flatMap { it.sourceRoots } ?: emptyList())
    ).toMutableMap()

    val resultByPath = LinkedHashMap<String, ContentRootData>()
    baseContentRoots.forEach { cr ->
        val newSourceRoots = cr.sourceRoots.mapNotNull { allSourceRootsByRank.remove(it.path) }
        val existing = resultByPath[cr.path]
        resultByPath[cr.path] = existing?.let { it.copy(sourceRoots = it.sourceRoots + newSourceRoots) }
            ?: cr.copy(sourceRoots = newSourceRoots)
    }

    // Source roots present only on the deps side (or in a deps CR with a path missing from gen)
    // are still in the rank map. Place them into their original deps content root, merging into
    // an existing same-path result entry when one already exists.
    if (genContentRoots != null && allSourceRootsByRank.isNotEmpty()) {
        depsContentRoots.forEach { depsCr ->
            val orphans = depsCr.sourceRoots.mapNotNull { allSourceRootsByRank.remove(it.path) }
            if (orphans.isEmpty()) return@forEach
            val existing = resultByPath[depsCr.path]
            resultByPath[depsCr.path] = existing?.let { it.copy(sourceRoots = it.sourceRoots + orphans) }
                ?: depsCr.copy(sourceRoots = orphans)
        }
    }

    return this.copy(
        contentRoots = resultByPath.values.toList()
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

