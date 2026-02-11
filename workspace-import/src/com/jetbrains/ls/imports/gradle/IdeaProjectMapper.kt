// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.JavaSettingsData
import com.jetbrains.ls.imports.json.KotlinSettingsData
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.SourceRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.XmlElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.tooling.model.ExternalDependency
import org.gradle.tooling.model.HierarchicalElement
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

internal object IdeaProjectMapper {

    private val LOG = logger<IdeaProjectMapper>()

    fun toWorkspaceData(metadata: ProjectMetadata): WorkspaceData {
        val sdks: MutableList<SdkData> = mutableListOf()
        val javaSettings: MutableList<JavaSettingsData> = mutableListOf()
        val modules = mutableMapOf<String, ModuleData>()

        val allGradleModules: List<IdeaModule> = metadata.includedProjects.flatMap { it.modules }
        // all project modules should be calculated before processing to provide a proper module substitution between projects
        val knownModules: List<String> = allGradleModules.map { it.getFqdn() }

        allGradleModules
            .map {
                splitModulePerSourceSet(
                    it,
                    metadata,
                    knownModules,
                    { moduleJavaSettings -> javaSettings.add(moduleJavaSettings) },
                    { sdk -> sdks.add(sdk) }
                )
            }
            .forEach { modules.putAll(it) }

        return WorkspaceData(
            modules = modules.values.toList(),
            libraries = calculateProjectLibraries(allGradleModules),
            sdks = sdks,
            javaSettings = javaSettings,
            kotlinSettings = calculateKotlinSettings(modules, metadata.kotlinModules)
        )
    }

    private fun calculateKotlinSettings(
        modules: Map<String, ModuleData>,
        kotlinModules: Map<String, KotlinModule>
    ): List<KotlinSettingsData> {
        val result = mutableListOf<KotlinSettingsData>()
        for ((name, moduleData) in modules) {
            if (!moduleData.hasValidSourceRoots()) {
                continue
            }
            val kotlinModuleKey = name.removeSuffix(".main")
                .removeSuffix(".test")
            val kotlinModule = kotlinModules[kotlinModuleKey]
            if (kotlinModule == null) {
                continue
            }
            val compilerSettings = kotlinModule.compilerSettings
            val kotlinCompilerSettings = compilerSettings.let {
                Json.encodeToString(KotlinCompilerSettings(it.jvmTarget, it.pluginOptions, it.pluginClasspath))
            }
            result.add(
                KotlinSettingsData(
                    name = "Kotlin",
                    sourceRoots = moduleData.contentRoots
                        .flatMap { it.sourceRoots }
                        .map { it.path },
                    configFileItems = emptyList(),
                    module = name,
                    useProjectSettings = false,
                    implementedModuleNames = emptyList(),
                    dependsOnModuleNames = emptyList(),
                    additionalVisibleModuleNames = emptySet(),
                    productionOutputPath = null,
                    testOutputPath = null,
                    sourceSetNames = emptyList(),
                    isTestModule = name.endsWith("test"),
                    externalProjectId = name,
                    isHmppEnabled = true,
                    pureKotlinSourceFolders = emptyList(),
                    kind = KotlinSettingsData.KotlinModuleKind.DEFAULT,
                    compilerArguments = "J$kotlinCompilerSettings",
                    additionalArguments = compilerSettings.compilerArgs.joinToString(" "),
                    scriptTemplates = null,
                    scriptTemplatesClasspath = null,
                    copyJsLibraryFiles = false,
                    outputDirectoryForJsLibraryFiles = null,
                    targetPlatform = null,
                    externalSystemRunTasks = emptyList(),
                    version = 5,
                    flushNeeded = false
                )
            )
        }
        return result
    }

    private fun splitModulePerSourceSet(
        module: IdeaModule,
        metadata: ProjectMetadata,
        knownModules: List<String>,
        javaSettingsConsumer: (JavaSettingsData) -> Unit,
        sdkConsumer: (SdkData) -> Unit
    ): Map<String, ModuleData> {
        val moduleName = module.getFqdn()
        val modules = mutableMapOf<String, ModuleData>()
        val moduleSdk = getSdkData(module)
        if (moduleSdk != null) {
            sdkConsumer(moduleSdk)
        }
        val sdkDependencyData: DependencyData = if (moduleSdk != null) {
            DependencyData.Sdk(moduleSdk.name, moduleSdk.type)
        } else {
            DependencyData.InheritedSdk
        }
        modules[moduleName] = ModuleData(
            name = moduleName,
            dependencies = listOf(
                DependencyData.ModuleSource,
                sdkDependencyData
            ),
            contentRoots = listOf(
                ContentRootData(module.gradleProject.projectDirectory.path)
            )
        )
        val javaSettings = getJavaSettingsData(module)
        if (javaSettings != null) {
            javaSettingsConsumer(javaSettings)
        }
        val associatedSourceSets = metadata.sourceSets[moduleName]
        if (associatedSourceSets.isNullOrEmpty()) {
            LOG.info("$moduleName has an empty set of source sets")
            return modules
        }

        associatedSourceSets.forEach { sourceSet ->
            when {
                sourceSet.name == "main" -> {
                    val dependencies = module.dependencies
                        .filter { dependency -> dependency.scope.scope != "TEST" }
                        .mapNotNull { getDependencyData(knownModules, it) }
                        .toMutableList()
                    dependencies.add(DependencyData.ModuleSource)
                    dependencies.add(sdkDependencyData)
                    modules["$moduleName.main"] = ModuleData(
                        name = "$moduleName.main",
                        dependencies = dependencies,
                        contentRoots = sourceSet.toContentRootData(module.gradleProject.projectDirectory, false)
                    )
                    if (javaSettings != null) {
                        javaSettingsConsumer(
                            javaSettings.copy(module = "$moduleName.main")
                        )
                    }
                }

                sourceSet.name.lowercase().contains("test") -> {
                    val dependencies = module.dependencies
                        .mapNotNull { getDependencyData(knownModules, it) }
                        .toMutableList()
                    dependencies.add(DependencyData.ModuleSource)
                    dependencies.add(sdkDependencyData)
                    dependencies.add(DependencyData.Module(name = "$moduleName.main", scope = DependencyDataScope.COMPILE))
                    modules["$moduleName.${sourceSet.name}"] = ModuleData(
                        name = "$moduleName.${sourceSet.name}",
                        dependencies = dependencies,
                        contentRoots = sourceSet.toContentRootData(module.gradleProject.projectDirectory, true)
                    )
                    if (javaSettings != null) {
                        javaSettingsConsumer(
                            javaSettings.copy(module = "$moduleName.${sourceSet.name}")
                        )
                    }
                }

                else -> {
                    val dependencies = module.dependencies
                        .mapNotNull { getDependencyData(knownModules, it) }
                        .toMutableList()
                    dependencies.add(DependencyData.ModuleSource)
                    dependencies.add(sdkDependencyData)
                    dependencies.add(DependencyData.Module(name = "$moduleName.main", scope = DependencyDataScope.COMPILE))
                    modules["$moduleName.${sourceSet.name}"] = ModuleData(
                        name = "$moduleName.${sourceSet.name}",
                        dependencies = dependencies,
                        contentRoots = sourceSet.toContentRootData(module.gradleProject.projectDirectory, false)
                    )
                    if (javaSettings != null) {
                        javaSettingsConsumer(
                            javaSettings.copy(module = "$moduleName.${sourceSet.name}")
                        )
                    }
                }
            }
        }
        return modules
    }

    private fun ModuleSourceSet.toContentRootData(moduleRoot: File, isTest: Boolean): List<ContentRootData> {
        val sourceRoots = mutableListOf<SourceRootData>()
        for (sourceRootFolder in sources) {
            if (sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots.add(
                    SourceRootData(
                        sourceRootFolder.path,
                        getSourceFolderType(sourceRootFolder, isTest)
                    )
                )
            }
        }
        for (sourceRootFolder in resources) {
            if (sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots.add(
                    SourceRootData(
                        sourceRootFolder.path,
                        if (isTest) "java-test-resource" else "java-resource"
                    )
                )
            }
        }
        var commonRoot = findRootForSourceRoots(sourceRoots)
        if (commonRoot.isEmpty()) {
            commonRoot = if (isTest) "$moduleRoot/src/test" else "$moduleRoot/src/main"
        }
        return listOf(
            ContentRootData(
                commonRoot,
                emptyList(),
                excludes.toMutableList(),
                sourceRoots = sourceRoots
            )
        )
    }

    private fun getSourceFolderType(file: File, isTest: Boolean): String {
        val folderName = file.name
        val prefix = when (folderName.lowercase()) {
            "kotlin" -> "kotlin"
            "groovy" -> "groovy"
            else -> "java"
        }
        return if (isTest) "$prefix-test" else "$prefix-source"
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

    private fun ModuleData.hasValidSourceRoots(): Boolean {
        return contentRoots
            .flatMap { it.sourceRoots }
            .any { Path.of(it.path).exists() }
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
        knownModules: List<String>,
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

    @Serializable
    private data class KotlinCompilerSettings(
        val jvmTarget: String?,
        val pluginOptions: List<String>,
        val pluginClasspath: List<String>
    )
}
