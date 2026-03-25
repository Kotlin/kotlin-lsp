// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.AndroidProject
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.XmlElement
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectArtifactDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.artifactsClasspath
import java.io.File
import kotlin.collections.component1
import kotlin.collections.component2

private val LOG = logger<SourceSetDependencyResolver>()

internal class SourceSetDependencyResolver(
    private val project: ProjectMetadata,
) {

    private val allIdeaModules = project.includedProjects.flatMap { it.modules }

    private val allModuleFqns = project.sourceSets.keys

    private val androidProjectsByBuildTreePath = project.androidProjects.values
        .associateBy { androidProject -> androidProject.buildTreePath }

    private val libraries: MutableSet<LibraryData> = mutableSetOf()
    private val dependencies: MutableMap<File, DependencyData> = mutableMapOf()

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

        val sourceSetDependencies = mutableSetOf<DependencyData>()

        for (file in moduleSourceSet.compileClasspath) {
            val dependencyData = resolveDependencyData(file) {
                if (it is DependencyData.Module) {
                    when {
                        compileModules.contains(it.name) -> DependencyDataScope.COMPILE
                        else -> DependencyDataScope.PROVIDED
                    }
                } else {
                    when {
                        compileDependencies.contains(file) -> DependencyDataScope.COMPILE
                        else -> DependencyDataScope.PROVIDED
                    }
                }
            }
            if (dependencyData != null && !dependencyData.isSelfReference(moduleName, moduleSourceSet)) {
                sourceSetDependencies.add(dependencyData)
            }
        }
        for (file in moduleSourceSet.runtimeClasspath) {
            val dependencyData = resolveDependencyData(file) {
                if (it is DependencyData.Module) {
                    when {
                        compileModules.contains(it.name) -> DependencyDataScope.COMPILE
                        else -> DependencyDataScope.PROVIDED
                    }
                } else {
                    when {
                        compileDependencies.contains(file) -> DependencyDataScope.COMPILE
                        else -> DependencyDataScope.RUNTIME
                    }
                }
            }
            if (dependencyData != null && !dependencyData.isSelfReference(moduleName, moduleSourceSet)) {
                sourceSetDependencies.add(dependencyData)
            }
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
            .filter { dependency ->
                if (!isTest) {
                    dependency.scope.scope != "TEST"
                } else {
                    true
                }
            }
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

    fun getProjectLibraries(): List<LibraryData> = libraries.toList()

    private fun ExternalModuleDependency.toLibraryData(moduleName: String): LibraryData {
        return LibraryData(
            name = "Gradle: ${mavenCoordinates}",
            module = moduleName,
            type = "COMPILE",
            roots = this.let {
                val result = mutableListOf<LibraryRootData>()
                it.file.run { if (exists()) result.add(LibraryRootData(path, "CLASSES")) }
                result
            }
        )
    }

    private fun ExternalModuleDependency.toDependencyData(): DependencyData {
        return DependencyData.Library(
            "Gradle: ${mavenCoordinates}",
            DependencyDataScope.RUNTIME,
            false
        )
    }

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
            .mapNotNull { dependencies[it] }
            .filterIsInstance<DependencyData.Module>()
            .toSet()
        val runtimeClasspathModules = runtimeClasspath
            .mapNotNull { dependencies[it] }
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
                dependencies.computeIfAbsent(dependency.file) {
                    DependencyData.Library(
                        dependency.getLibraryName(),
                        DependencyDataScope.RUNTIME,
                        dependency.isExportedSafe()
                    )
                }
                libraries.add(dependency.toLibraryData(module.name))
            }
    }

    private fun populateDependenciesFromModuleDependencies() {
        project.moduleDependencies.forEach { (moduleName, moduleDependencies) ->
            moduleDependencies.forEach { dependency ->
                dependencies.computeIfAbsent(dependency.file) {
                    val data = dependency.toLibraryData(moduleName)
                    libraries.add(data)
                    dependency.toDependencyData()
                }
            }
        }
    }

    private fun populateDependenciesFromSourceSetOutputs() {
        project.sourceSets.forEach { (moduleFqdn, sourceSets) ->
            for (sourceSet in sourceSets) {
                for (producedArtifact in sourceSet.sourceSetOutput) {
                    dependencies.computeIfAbsent(producedArtifact) {
                        DependencyData.Module(
                            name = "$moduleFqdn.${sourceSet.name}",
                            scope = DependencyDataScope.RUNTIME,
                            isExported = false
                        )
                    }
                }
            }
        }
    }

    private fun populateDependenciesForAndroidModule(androidProject: AndroidProject, module: IdeaModule) {
        val artifacts = hashSetOf<File>()

        androidProject.dependencies.forEach { dependency ->
            if (dependency is IdeaKotlinResolvedBinaryDependency) {
                val libraryName = dependency.libraryName() ?: return@forEach
                artifacts += dependency.classpath
                libraries += LibraryData(
                    name = libraryName,
                    module = module.name,
                    type = "COMPILE",
                    roots = dependency.classpath.map { LibraryRootData(it.path, "CLASSES") },
                    properties = dependency.getProperties()
                )

                dependency.classpath.forEach { artifactFile ->
                    dependencies.computeIfAbsent(artifactFile) {
                        DependencyData.Library(
                            name = libraryName,
                            scope = DependencyDataScope.COMPILE,
                            isExported = false
                        )
                    }
                }
            }

            if (dependency is IdeaKotlinProjectArtifactDependency) {
                dependency.artifactsClasspath.forEach { artifactFile ->
                    val targetProject = androidProjectsByBuildTreePath[dependency.coordinates.projectPath] ?: return@forEach
                    val targetProjectFqn = project.androidProjects.entries.firstOrNull { it.value == targetProject }?.key ?: return@forEach

                    dependencies[artifactFile] = DependencyData.Module(
                        "$targetProjectFqn.${targetProject.activeVariant}",
                        DependencyDataScope.COMPILE,
                        isExported = false
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

                    libraries += LibraryData(
                        name = libraryName,
                        module = module.name,
                        type = "COMPILE",
                        roots = listOf(LibraryRootData(artifactFile.path, "CLASSES")),
                    )

                    dependencies.computeIfAbsent(artifactFile) {
                        DependencyData.Library(
                            libraryName,
                            DependencyDataScope.COMPILE,
                            isExported = false
                        )
                    }
                }
            }
        }
    }


    private fun resolveDependencyData(
        file: File, getScope: (dependencyData: DependencyData) -> DependencyDataScope
    ): DependencyData? {
        val dependencyData = dependencies[file] ?: run {
            LOG.warn("Unresolved dependency file $file")
            return null
        }
        if (dependencyData is DependencyData.Library) {
            return DependencyData.Library(dependencyData.name, getScope(dependencyData), dependencyData.isExported)
        }
        if (dependencyData is DependencyData.Module) {
            return DependencyData.Module(dependencyData.name, getScope(dependencyData), dependencyData.isExported, dependencyData.isTestJar)
        }
        LOG.warn("Unexpected dependency type $dependencyData of $file")
        return null
    }

    private fun IdeaSingleEntryLibraryDependency.toLibraryData(moduleName: String): LibraryData {
        val libraryName = getLibraryName()
        return LibraryData(
            name = libraryName,
            module = moduleName,
            type = scope.scope,
            roots = this.let {
                val result = mutableListOf<LibraryRootData>()
                it.file?.run { if (exists()) result.add(LibraryRootData(path, "CLASSES")) }
                it.source?.run { if (exists()) result.add(LibraryRootData(path, "SOURCES")) }
                it.javadoc?.run { if (exists()) result.add(LibraryRootData(path, "JAVADOC")) }
                result
            },
            properties = getProperties()
        )
    }

    private fun IdeaSingleEntryLibraryDependency.getProperties(): XmlElement? {
        if (gradleModuleVersion == null) {
            return null
        }
        val metadata = mutableMapOf<String, String>()
        metadata.putNotNullValue("groupId", gradleModuleVersion.group)
        metadata.putNotNullValue("artifactId", gradleModuleVersion.name)
        metadata.putNotNullValue("version", gradleModuleVersion.version)
        metadata.putNotNullValue("baseVersion", gradleModuleVersion.version)
        if (metadata.isEmpty()) {
            return null
        }
        return XmlElement(
            tag = "properties",
            attributes = metadata
        )
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

    private fun <K, V> MutableMap<K, V>.putNotNullValue(key: K, value: V?) {
        if (value != null) {
            put(key, value)
        }
    }

    private fun IdeaDependency.isExportedSafe(): Boolean {
        return try {
            when (this) {
                is IdeaSingleEntryLibraryDependency -> isExported
                is IdeaModuleDependency -> exported
                else -> false
            }
        } catch (_: UnsupportedMethodException) {
            false
        }
    }

    private fun DependencyData.isSelfReference(moduleName: String, sourceSet: ModuleSourceSet): Boolean {
        return if (this is DependencyData.Module) {
            name == "$moduleName.${sourceSet.name}"
        } else {
            false
        }
    }
}

private fun IdeaKotlinResolvedBinaryDependency.libraryName(): String? {
    return coordinates?.run {
        "Gradle: $group:$module:$version"
    }
}