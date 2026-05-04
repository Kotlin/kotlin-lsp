// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.AndroidProject
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.gradle.util.DependencyDataScopeCalculator
import com.jetbrains.ls.imports.gradle.util.DependencyFileIndex
import com.jetbrains.ls.imports.gradle.util.ProjectLibraryIndex
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.XmlElement
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectArtifactDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.artifactsClasspath
import java.io.File

internal class SourceSetDependencyResolver(
    private val project: ProjectMetadata,
) {

    private val allIdeaModules = project.includedProjects.flatMap { it.modules }

    private val allModuleFqns = project.sourceSets.keys

    private val androidProjectsByBuildTreePath = project.androidProjects.values
        .associateBy { androidProject -> androidProject.buildTreePath }

    private val projectLibraryIndex: ProjectLibraryIndex = ProjectLibraryIndex()
    private val dependencyFileIndex: DependencyFileIndex = DependencyFileIndex()

    init {
        populateDependenciesFromSourceSetOutputs()
        allIdeaModules.forEach { module ->
            populateDependenciesFromIdeaModule(module)
            project.androidProjects[module.name]?.let { androidProject ->
                populateDependenciesForAndroidModule(androidProject, module)
            }
        }
        populateDependenciesFromModuleDependencies()
    }


    /**
     * Dependencies that present in test source set are marked with TEST.
     * Dependencies that present only in the compile scope are marked with PROVIDED.
     * Dependencies that present only in the runtime scope are marked with RUNTIME.
     * Dependencies that present in the both scopes are marked with COMPILE.
     */
    fun resolveDependencies(moduleName: String, moduleSourceSet: ModuleSourceSet): List<DependencyData> {
        val compileDependencies = moduleSourceSet.compileClasspath.intersect(moduleSourceSet.runtimeClasspath)
        val compileModules = moduleSourceSet.getCompileModules()
        val providedScopeMatcher = DependencyDataScopeCalculator.forProvided(compileDependencies, compileModules)
        val runtimeScopeMatcher = DependencyDataScopeCalculator.forRuntime(compileDependencies, compileModules)

        val sourceSetDependencies = mutableSetOf<DependencyData>()

        for (file in moduleSourceSet.compileClasspath) {
            dependencyFileIndex.get(file, providedScopeMatcher)
                .filter { !it.isSelfReference(moduleName, moduleSourceSet) }
                .forEach { sourceSetDependencies.add(it) }
        }
        for (file in moduleSourceSet.runtimeClasspath) {
            dependencyFileIndex.get(file, runtimeScopeMatcher)
                .filter { !it.isSelfReference(moduleName, moduleSourceSet) }
                .forEach { sourceSetDependencies.add(it) }
        }
        return sourceSetDependencies.toList()
    }

    /**
     * This is a naive fallback in case of an error during configuration resolution.
     */
    fun resolveDependenciesFromIdeaModule(
        module: IdeaModule,
        moduleSourceSet: ModuleSourceSet
    ): List<DependencyData> {
        val isTest = moduleSourceSet.isTest()
        val dependencies = module.dependencies
            .filter { dependency -> isTest || dependency.scope.scope != "TEST" }
            .mapNotNull { it.toDependencyData() }
            .toMutableList()
        if (isTest) {
            dependencies.add(
                DependencyData.Module(
                    module.name + ".main",
                    DependencyDataScope.COMPILE
                )
            )
        }
        return dependencies
    }

    fun getProjectLibraries(): List<LibraryData> = projectLibraryIndex.getLibraries()

    private fun IdeaDependency.toDependencyData(): DependencyData? {
        return when (this) {
            is IdeaSingleEntryLibraryDependency -> {
                val libraryName = getLibraryName()
                DependencyData.Library(
                    name = libraryName,
                    scope = DependencyDataScope.valueOf(scope.scope),
                    isExported = isExportedSafe()
                )
            }

            is IdeaModuleDependency -> DependencyData.Module(
                name = (allModuleFqns.find { it.endsWith(".${targetModuleName}") } ?: targetModuleName) + ".main",
                scope = DependencyDataScope.valueOf(scope.scope),
                isExported = isExportedSafe()
            )

            else -> null
        }
    }

    private fun ModuleSourceSet.getCompileModules(): List<String> {
        val providedClasspathModules = compileClasspath
            .flatMap { dependencyFileIndex.get(it) }
            .filterIsInstance<DependencyData.Module>()
            .toSet()
        val runtimeClasspathModules = runtimeClasspath
            .flatMap { dependencyFileIndex.get(it) }
            .filterIsInstance<DependencyData.Module>()
            .toSet()
        return providedClasspathModules.intersect(runtimeClasspathModules)
            .map { it.name }
    }

    private fun populateDependenciesFromIdeaModule(module: IdeaModule) {
        module.dependencies
            .filterIsInstance<IdeaSingleEntryLibraryDependency>()
            .distinctBy { it.file }
            .forEach { dependency ->
                dependencyFileIndex.add(dependency)
                projectLibraryIndex.add(module.name, dependency)
            }
    }

    private fun populateDependenciesFromModuleDependencies() {
        project.moduleDependencies.forEach { (moduleName, moduleDependencies) ->
            moduleDependencies.forEach { dependency ->
                dependencyFileIndex.add(dependency)
                projectLibraryIndex.add(moduleName, dependency)
            }
        }
    }

    private fun populateDependenciesFromSourceSetOutputs() {
        project.sourceSets.forEach { (moduleFqn, sourceSets) ->
            for (sourceSet in sourceSets) {
                dependencyFileIndex.add(moduleFqn, sourceSet)
            }
        }
    }

    private fun populateDependenciesForAndroidModule(androidProject: AndroidProject, module: IdeaModule) {
        val artifacts = hashSetOf<File>()

        androidProject.dependencies.forEach { dependency ->
            if (dependency is IdeaKotlinResolvedBinaryDependency) {
                val libraryName = dependency.libraryName() ?: return@forEach
                artifacts += dependency.classpath
                projectLibraryIndex.add(
                    LibraryData(
                    name = libraryName,
                    module = module.name,
                    type = "COMPILE",
                    roots = dependency.classpath.map { LibraryRootData(it.path, "CLASSES") },
                    properties = dependency.getProperties()
                    )
                )
                dependency.classpath.forEach { artifactFile ->
                    dependencyFileIndex.add(
                        artifactFile,
                        DependencyData.Library(
                            name = libraryName,
                            scope = DependencyDataScope.COMPILE,
                            isExported = false
                        )
                    )
                }
            }

            if (dependency is IdeaKotlinProjectArtifactDependency) {
                dependency.artifactsClasspath.forEach { artifactFile ->
                    val targetProject = androidProjectsByBuildTreePath[dependency.coordinates.projectPath] ?: return@forEach
                    val targetProjectFqn = project.androidProjects.entries.firstOrNull { it.value == targetProject }?.key ?: return@forEach
                    dependencyFileIndex.add(
                        artifactFile,
                        DependencyData.Module(
                            "$targetProjectFqn.${targetProject.activeVariant}",
                            DependencyDataScope.COMPILE,
                            isExported = false
                        )
                    )
                }
            }
        }


        /*
         Ad-hoc dependencies that are resolved for the corresponding source set but were not resolved in the
         corresponding 'dependency scopes'. Such dependencies are can contain special 'synthetic' android jars
         (e.g., android.jar, R.jar, etc.)
         */
        val sourceSets = project.sourceSets[module.name].orEmpty()
        sourceSets.forEach { sourceSet ->
            sourceSet.compileClasspath.forEach { artifactFile ->
                if (artifacts.add(artifactFile)) {
                    val libraryName = "Gradle: ${artifactFile.path}"
                    projectLibraryIndex.add(
                        LibraryData(
                        name = libraryName,
                        module = module.name,
                        type = "COMPILE",
                        roots = listOf(LibraryRootData(artifactFile.path, "CLASSES")),
                        )
                    )
                    dependencyFileIndex.add(
                        artifactFile,
                        DependencyData.Library(
                            libraryName,
                            DependencyDataScope.COMPILE,
                            isExported = false
                        )
                    )
                }
            }
        }
    }

    private fun IdeaKotlinResolvedBinaryDependency.getProperties(): XmlElement? {
        val metadata = mutableMapOf<String, String>()
        metadata.putNotNullValue("groupId", coordinates?.group)
        metadata.putNotNullValue("artifactId", coordinates?.module)
        metadata.putNotNullValue("version", coordinates?.version)
        metadata.putNotNullValue("baseVersion", coordinates?.version)
        if (metadata.isEmpty()) {
            return null
        }
        return XmlElement(
            tag = "properties",
            attributes = metadata
        )
    }

    private fun DependencyData.isSelfReference(moduleName: String, sourceSet: ModuleSourceSet): Boolean {
        return this is DependencyData.Module && name == "$moduleName.${sourceSet.name}"
    }
}

private fun IdeaKotlinResolvedBinaryDependency.libraryName(): String? {
    return coordinates?.run {
        "Gradle: $group:$module:$version"
    }
}