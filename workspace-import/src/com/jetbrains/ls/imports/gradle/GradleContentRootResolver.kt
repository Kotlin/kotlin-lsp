// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.util.io.PathPrefixTree
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.SourceRootData
import org.gradle.tooling.model.idea.IdeaModule
import java.nio.file.Path

@Suppress("IO_FILE_USAGE")
class GradleContentRootResolver(metadata: ProjectMetadata) {

    private val contentRootWeightMap = HashMap<Path, Int>()

    init {
        metadata.sourceSets
            .flatMap { it.value }
            .forEach {
                val sourceRoots = getSourceRoots(it)
                addSourceRoots(sourceRoots)
            }
    }

    fun getContentRoots(module: IdeaModule, sourceSet: ModuleSourceSet): List<ContentRootData> {
        val contentRoots = resolveContentRoots(
            module,
            sourceSet
        )
        val isTest = sourceSet.isTest()
        val sourceRoots = mutableListOf<SourceRootData>()
        for (sourceRootFolder in sourceSet.sources) {
            if (sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots.add(
                    SourceRootData(
                        sourceRootFolder.path,
                        if (isTest) "java-test" else "java-source"
                    )
                )
            }
        }
        for (sourceRootFolder in sourceSet.resources) {
            if (sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots.add(
                    SourceRootData(
                        sourceRootFolder.path,
                        if (isTest) "java-test-resource" else "java-resource"
                    )
                )
            }
        }
        return contentRoots.map { contentRoot ->
            ContentRootData(
                contentRoot.toString(),
                emptyList(),
                sourceSet.excludes.toMutableList(),
                sourceRoots = sourceRoots
                    .filter { sourceRoot -> Path.of(sourceRoot.path).startsWith(contentRoot) }
            )
        }
    }

    private fun addSourceRoots(sourceRoots: Collection<Path>) {
        val contentRoots = HashSet<Path>()
        for (sourceRoot in sourceRoots) {
            contentRoots.addAll(resolveParentPaths(sourceRoot))
        }
        for (contentRoot in contentRoots) {
            val contentRootWeight = contentRootWeightMap.getOrDefault(contentRoot, 0)
            contentRootWeightMap[contentRoot] = contentRootWeight + 1
        }
    }

    private fun resolveContentRoots(module: IdeaModule, sourceSet: ModuleSourceSet): Set<Path> {
        val sourceRoots = getSourceRoots(sourceSet)
        val projectRootPath = module.gradleProject.projectDirectory.toPath()
        val buildRootPath = module.gradleProject.buildDirectory.toPath()
        return resolveContentRoots(projectRootPath, buildRootPath, sourceRoots)
    }

    private fun resolveContentRoots(projectRootPath: Path, buildRootPath: Path, sourceRoots: Collection<Path>): Set<Path> {
        val contentRoots = PathPrefixTree.createSet()
        for (sourceRootPath in sourceRoots) {
            val contentRootPath = resolveContentRoot(projectRootPath, buildRootPath, sourceRootPath)
            contentRoots.add(contentRootPath)
        }
        return contentRoots.getRoots()
    }

    private fun resolveContentRoot(projectRootPath: Path, buildRootPath: Path, sourceRootPath: Path): Path {
        if (sourceRootPath.startsWith(buildRootPath)) {
            return sourceRootPath
        }
        val contentRootPath = sourceRootPath.parent
        if (contentRootPath == null || contentRootPath == projectRootPath) {
            return sourceRootPath
        }
        val contentRootWeight = contentRootWeightMap[contentRootPath]
        if (contentRootWeight == null || contentRootWeight > 1) {
            return sourceRootPath
        }
        return contentRootPath
    }

    private fun resolveParentPaths(path: Path): List<Path> {
        val result = ArrayList<Path>()
        var parentPath: Path? = path
        while (parentPath != null) {
            result.add(parentPath)
            parentPath = parentPath.parent
        }
        return result
    }

    private fun getSourceRoots(sourceSet: ModuleSourceSet): Collection<Path> {
        return sourceSet.sources.map { it.toPath() } + sourceSet.resources.map { it.toPath() }
    }
}