// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
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
import java.io.File

private val LOG = logger<SourceSetDependencyResolver>()

internal class SourceSetDependencyResolver {

    private val fileLibraryCache: MutableMap<File, DependencyData> = mutableMapOf()
    private val projectDependencies: MutableSet<LibraryData> = mutableSetOf()
    private val knownModules: MutableSet<String> = mutableSetOf()

    fun init(modules: List<IdeaModule>, moduleSourceSets: Map<String, Set<ModuleSourceSet>>) {
        moduleSourceSets.forEach { (moduleFqdn, sourceSets) ->
            knownModules.add(moduleFqdn)
            for (sourceSet in sourceSets) {
                for (producedArtifact in sourceSet.sourceSetOutput) {
                    fileLibraryCache[producedArtifact] = DependencyData.Module(
                        name = "$moduleFqdn.${sourceSet.name}",
                        scope = DependencyDataScope.RUNTIME,
                        isExported = false
                    )
                }
            }
        }
        for (module in modules) {
            populateCacheWithModuleLibraryDependencies(module)
        }
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
            .mapNotNull { getDependencyDataFromGradleModel(it) }
            .toMutableList()
        if (isTest) {
            dependencies.add(
                DependencyData.Module(
                    module.getFqdn() + ".main",
                    DependencyDataScope.COMPILE
                )
            )
        }
        return dependencies
    }

    fun getProjectDependencies(): List<LibraryData> = projectDependencies.toList()

    private fun getDependencyDataFromGradleModel(dependency: IdeaDependency): DependencyData? {
        return when (dependency) {
            is IdeaSingleEntryLibraryDependency -> {
                val libraryName = dependency.getLibraryName()
                DependencyData.Library(
                    name = libraryName,
                    scope = DependencyDataScope.valueOf(dependency.scope.scope),
                    isExported = dependency.isExportedSafe()
                )
            }

            is IdeaModuleDependency -> DependencyData.Module(
                name = (knownModules.find { it.endsWith(".${dependency.targetModuleName}") } ?: dependency.targetModuleName) + ".main",
                scope = DependencyDataScope.valueOf(dependency.scope.scope),
                isExported = dependency.isExportedSafe()
            )

            else -> null
        }
    }

    private fun ModuleSourceSet.getCompileModules(): List<String> {
        val providedClasspathModules = compileClasspath
            .mapNotNull { fileLibraryCache[it] }
            .filterIsInstance<DependencyData.Module>()
            .toSet()
        val runtimeClasspathModules = runtimeClasspath
            .mapNotNull { fileLibraryCache[it] }
            .filterIsInstance<DependencyData.Module>()
            .toSet()
        return providedClasspathModules.intersect(runtimeClasspathModules)
            .map { it.name }
    }

    private fun populateCacheWithModuleLibraryDependencies(module: IdeaModule) {
        module.dependencies
            .filterIsInstance<IdeaSingleEntryLibraryDependency>()
            .forEach { dependency ->
                fileLibraryCache.computeIfAbsent(dependency.file) {
                    DependencyData.Library(
                        dependency.getLibraryName(),
                        DependencyDataScope.RUNTIME,
                        dependency.isExportedSafe()
                    )
                }
                projectDependencies.add(getLibraryData(dependency, module))
            }
    }

    private fun resolveDependencyData(file: File, getScope: (dependencyData: DependencyData) -> DependencyDataScope): DependencyData? {
        val dependencyData = fileLibraryCache[file]
        if (dependencyData == null) {
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

    private fun getLibraryData(dependency: IdeaSingleEntryLibraryDependency, module: IdeaModule): LibraryData {
        val libraryName = dependency.getLibraryName()
        return LibraryData(
            name = libraryName,
            module = module.name,
            type = dependency.scope.scope,
            roots = dependency.let {
                val result = mutableListOf<LibraryRootData>()
                it.file?.run { if (exists()) result.add(LibraryRootData(path, "CLASSES")) }
                it.source?.run { if (exists()) result.add(LibraryRootData(path, "SOURCES")) }
                it.javadoc?.run { if (exists()) result.add(LibraryRootData(path, "JAVADOC")) }
                result
            },
            properties = dependency.getProperties()
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
