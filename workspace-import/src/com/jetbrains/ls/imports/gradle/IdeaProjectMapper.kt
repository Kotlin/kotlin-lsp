// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.json.*
import org.gradle.tooling.model.ExternalDependency
import org.gradle.tooling.model.HierarchicalElement
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.*

internal object IdeaProjectMapper {

    fun toWorkspaceData(project: IdeaProject): WorkspaceData {
        val sdks: MutableList<SdkData> = mutableListOf()
        val javaSettings: MutableList<JavaSettingsData> = mutableListOf()
        val knownModules = project.modules.map { it.getFqdn() }
            .toSet()

        val modules = project.modules.flatMap { module ->
            calculateModules(module, knownModules, { moduleJavaSettings ->
                javaSettings.add(moduleJavaSettings)
            }, { sdk ->
                sdks.add(sdk)
            })
        }
        return WorkspaceData(
            modules = modules,
            libraries = calculateProjectLibraries(project.modules.toList()),
            sdks = sdks,
            javaSettings = javaSettings
        )
    }

    private fun calculateModules(
        module: IdeaModule,
        knownModules: Set<String>,
        javaSettingsConsumer: (JavaSettingsData) -> Unit,
        sdkConsumer: (SdkData) -> Unit
    ): List<ModuleData> {
        val moduleName = module.getFqdn()
        val modules = mutableListOf<ModuleData>()
        val moduleSdk = getSdkData(module)
        if (moduleSdk != null) {
            sdkConsumer(moduleSdk)
        }
        val sdkDependencyData: DependencyData = if (moduleSdk != null) {
            DependencyData.Sdk(moduleSdk.name, moduleSdk.type)
        } else {
            DependencyData.InheritedSdk
        }
        modules.add(
            ModuleData(
                name = moduleName
            )
        )
        val javaSettings = getJavaSettingsData(module)
        if (javaSettings != null) {
            javaSettingsConsumer(javaSettings)
        }
        module.contentRoots.forEach { contentRoot ->
            if (contentRoot.testDirectories.isNotEmpty() || contentRoot.testResourceDirectories.isNotEmpty()) {
                val sourceRoots = mutableListOf<SourceRootData>()
                    .apply {
                        contentRoot.testDirectories
                            .forEach {
                                add(SourceRootData(it.directory.path, "java-test"))
                            }
                        contentRoot.testResourceDirectories
                            .forEach {
                                add(SourceRootData(it.directory.path, "java-test-resource"))
                            }
                    }
                val contentRoot = ContentRootData(
                    findRootForSourceRoots(sourceRoots),
                    emptyList(),
                    contentRoot.excludeDirectories.map { it.path },
                    sourceRoots = sourceRoots
                )
                val dependencies = module.dependencies
                    .mapNotNull { getDependencyData(knownModules, it) }
                    .toMutableList()
                dependencies.add(DependencyData.ModuleSource)
                dependencies.add(sdkDependencyData)
                dependencies.add(DependencyData.Module(name = "$moduleName.main", scope = DependencyDataScope.COMPILE))
                modules.add(
                    ModuleData(
                    name = "$moduleName.test",
                    dependencies = dependencies,
                    contentRoots = listOf(contentRoot)
                    )
                )
                if (javaSettings != null) {
                    javaSettingsConsumer(
                        javaSettings.copy(module = "$moduleName.test")
                    )
                }
            }
            if (contentRoot.sourceDirectories.isNotEmpty() || contentRoot.resourceDirectories.isNotEmpty()) {
                val sourceRoots = mutableListOf<SourceRootData>()
                    .apply {
                        contentRoot.sourceDirectories
                            .forEach {
                                add(SourceRootData(it.directory.path, "java-source"))
                            }
                        contentRoot.resourceDirectories
                            .forEach {
                                add(SourceRootData(it.directory.path, "java-resource"))
                            }
                    }
                val contentRoot = ContentRootData(
                    findRootForSourceRoots(sourceRoots),
                    emptyList(),
                    contentRoot.excludeDirectories.map { it.path },
                    sourceRoots = sourceRoots
                )
                val dependencies = module.dependencies
                    .filter { it.scope.scope != "TEST" }
                    .mapNotNull { getDependencyData(knownModules, it) }
                    .toMutableList()
                dependencies.add(DependencyData.ModuleSource)
                dependencies.add(sdkDependencyData)
                modules.add(
                    ModuleData(
                    name = "$moduleName.main",
                    dependencies = dependencies,
                    contentRoots = listOf(contentRoot)
                    )
                )
                if (javaSettings != null) {
                    javaSettingsConsumer(
                        javaSettings.copy(module = "$moduleName.main")
                    )
                }
            }
        }
        return modules
    }

    private fun IdeaModule.getFqdn(): String {
        var fqdn = name
        if (name == project.name) {
            return name
        }
        var currentParent: HierarchicalElement? = parent
        while (currentParent != null) {
            fqdn = "${currentParent.name}.$fqdn"
            currentParent = currentParent.parent
        }
        return fqdn
    }

    private fun findRootForSourceRoots(sourceRoots: List<SourceRootData>): String {
        return findCommonPrefix(sourceRoots.map { it.path })
    }

    private fun findCommonPrefix(strings: List<String>): String {
        if (strings.isEmpty()) {
            return ""
        }
        var result = ""
        strings.first()
            .indices
            .forEach { currentLetterIndex ->
                val currentChar = strings[0][currentLetterIndex]
                for (currentStringIndex in 1 until strings.size) {
                    if (
                        currentLetterIndex >= strings[currentStringIndex].length || strings[currentStringIndex][currentLetterIndex] != currentChar
                    ) {
                        return result
                    }
                }
                result += currentChar
            }
        return result
    }

    private fun calculateProjectLibraries(modules: List<IdeaModule>): List<LibraryData> {
        return modules.flatMap { module ->
            module.dependencies
                .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                .map { dependency ->
                    val libraryName = dependency.getLibraryName() ?: return@map null
                    return@map LibraryData(
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
                .filterNotNull()
        }

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

    private fun getJavaSettingsData(module: IdeaModule): JavaSettingsData? {
        return if (module.javaLanguageSettings.isSpecified()) {
            JavaSettingsData(
                module = module.getFqdn(),
                inheritedCompilerOutput = module.compilerOutput?.inheritOutputDirs ?: false,
                compilerOutput = module.compilerOutput?.outputDir?.path,
                compilerOutputForTests = module.compilerOutput?.testOutputDir?.path,
                languageLevelId = module.javaLanguageSettings?.targetBytecodeVersion?.name?.replace("VERSION", "JDK"),
                manifestAttributes = emptyMap(),
                excludeOutput = false
            )
        } else {
            null
        }
    }

    private fun IdeaJavaLanguageSettings?.isSpecified(): Boolean {
        return this != null && (jdk != null || languageLevel != null || targetBytecodeVersion != null)
    }

    private fun getSdkData(module: IdeaModule): SdkData? {
        return if (module.javaLanguageSettings.isSpecified()) {
            val jdkSettings = module.javaLanguageSettings?.jdk ?: return null
            SdkData(
                name = module.jdkName,
                type = "jdk",
                homePath = jdkSettings.javaHome?.path,
                version = jdkSettings.javaVersion?.name,
                additionalData = ""
            )
        } else {
            null
        }
    }

    private fun getDependencyData(
        knownModules: Set<String>,
        dependency: IdeaDependency
    ): DependencyData? {
        return when (dependency) {
            is IdeaSingleEntryLibraryDependency -> {
                val libraryName = dependency.getLibraryName() ?: return null
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

    private fun ExternalDependency.getLibraryName(): String? {
        if (gradleModuleVersion != null) {
            return "Gradle: ${gradleModuleVersion.group ?: ""}:${gradleModuleVersion.name ?: ""}:${gradleModuleVersion.version ?: ""}"
        }
        return null
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
}
