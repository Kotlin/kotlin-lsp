// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.util.io.PathPrefixTree
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.SourceRootData
import org.gradle.tooling.model.idea.IdeaModule
import java.io.File
import java.nio.file.Path

@Suppress("IO_FILE_USAGE")
class GradleContentRootResolver(metadata: ProjectMetadata) {

    private val contentRootWeightMap = HashMap<Path, Int>()

    init {
        metadata.sourceSets.forEach { (moduleFqn, sourceSets) ->
            // A Kotlin Multiplatform module replaces the Java source set of the same name (`jvmMain`), so its roots
            // are counted once, from the Kotlin source set below.
            val kotlinSourceSetNames = metadata.kotlinMultiplatformModels[moduleFqn]?.sourceSets.orEmpty().map { it.name }.toSet()
            sourceSets
                .filter { it.name !in kotlinSourceSetNames }
                .forEach { addSourceRoots(getSourceRoots(it)) }
        }
        metadata.kotlinMultiplatformModels.values
            .flatMap { it.sourceSets }
            .forEach { sourceSet ->
                addSourceRoots((sourceSet.sourceDirs + sourceSet.generatedSourceDirs + sourceSet.resourceDirs).map { it.toPath() })
            }
    }

    fun getContentRoots(module: IdeaModule, sourceSet: ModuleSourceSet): List<ContentRootData> =
        getContentRoots(module, sourceSet.sources, sourceSet.resources, sourceSet.excludes, sourceSet.isTest())

    /**
     * The content roots of a module with the given source and resource directories. Only existing directories become
     * source roots.
     */
    fun getContentRoots(
        module: IdeaModule,
        sources: Set<File>,
        resources: Set<File>,
        excludes: Set<String>,
        isTest: Boolean,
    ): List<ContentRootData> {
        val contentRoots = resolveContentRoots(module, sources.map { it.toPath() } + resources.map { it.toPath() })
        // A root under the build directory holds files a task wrote, e.g. the annotation-processor output.
        val buildRootPath = module.gradleProject.buildDirectory.toPath()
        val sourceRoots = mutableMapOf<File, SourceRootData>()
        for (sourceRootFolder in sources) {
            if (!sourceRoots.containsKey(sourceRootFolder) && sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots[sourceRootFolder] = SourceRootData(
                    sourceRootFolder.path,
                    if (isTest) "java-test" else "java-source",
                    generated = sourceRootFolder.toPath().startsWith(buildRootPath),
                )
            }
        }
        for (sourceRootFolder in resources) {
            if (!sourceRoots.containsKey(sourceRootFolder) && sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots[sourceRootFolder] = SourceRootData(
                    sourceRootFolder.path,
                    if (isTest) "java-test-resource" else "java-resource",
                    generated = sourceRootFolder.toPath().startsWith(buildRootPath),
                )
            }
        }
        return contentRoots.map { contentRoot ->
            ContentRootData(
                contentRoot.toString(),
                emptyList(),
                excludes.toMutableList(),
                sourceRoots = sourceRoots
                    .values
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

    private fun resolveContentRoots(module: IdeaModule, sourceRoots: Collection<Path>): Set<Path> {
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

/**
 * The same content root with the Kotlin source root types (`kotlin-source`, `kotlin-test`, `kotlin-resource`,
 * `kotlin-test-resource`), for the roots of non-JVM modules. The Java types would make the analyzer treat their files
 * as Java sources.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.base.projectStructure.getMigratedSourceRootTypeWithProperties`
 */
fun ContentRootData.withKotlinRootTypes(): ContentRootData = copy(
    sourceRoots = sourceRoots.map { root ->
        val kotlinType = when (root.type) {
            "java-source" -> "kotlin-source"
            "java-test" -> "kotlin-test"
            "java-resource" -> "kotlin-resource"
            "java-test-resource" -> "kotlin-test-resource"
            else -> root.type
        }
        root.copy(type = kotlinType)
    }
)
