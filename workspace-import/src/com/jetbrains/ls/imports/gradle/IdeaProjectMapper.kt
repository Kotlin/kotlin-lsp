// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.JavaSettingsData
import com.jetbrains.ls.imports.json.KotlinSettingsData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.SourceRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings
import org.gradle.tooling.model.idea.IdeaModule
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

internal class IdeaProjectMapper {

    private val LOG = logger<IdeaProjectMapper>()
    private val dependencyResolver: SourceSetDependencyResolver = SourceSetDependencyResolver()

    fun toWorkspaceData(metadata: ProjectMetadata): WorkspaceData {
        val sdks: MutableList<SdkData> = mutableListOf()
        val javaSettings: MutableList<JavaSettingsData> = mutableListOf()
        val modules = mutableMapOf<String, ModuleData>()

        val allGradleModules: List<IdeaModule> = metadata.includedProjects.flatMap { it.modules }
        dependencyResolver.init(allGradleModules, metadata.sourceSets)

        allGradleModules
            .map {
                splitModulePerSourceSet(
                    it,
                    metadata,
                    { moduleJavaSettings -> javaSettings.add(moduleJavaSettings) },
                    { sdk -> sdks.add(sdk) }
                )
            }
            .forEach { modules.putAll(it) }

        return WorkspaceData(
            modules = modules.values.toList(),
            libraries = dependencyResolver.getProjectDependencies(),
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
                Json.encodeToString(KotlinCompilerSettings(it.jvmTarget, it.pluginOptions, it.pluginClasspaths))
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
            val sourceSetDependencies = mutableListOf<DependencyData>()
                .apply {
                    if (sourceSet.hasUnresolvedDependencies()) {
                        addAll(dependencyResolver.resolveDependenciesFromIdeaModule(module, sourceSet))
                    } else {
                        addAll(dependencyResolver.resolveDependencies(moduleName, sourceSet))
                    }
                    add(DependencyData.ModuleSource)
                    add(sdkDependencyData)
                }

            modules["$moduleName.${sourceSet.name}"] = ModuleData(
                name = "$moduleName.${sourceSet.name}",
                dependencies = sourceSetDependencies,
                contentRoots = sourceSet.toContentRootData(
                    module.gradleProject.projectDirectory,
                    sourceSet.isTest()
                )
            )
            if (javaSettings != null) {
                javaSettingsConsumer(
                    javaSettings.copy(module = "$moduleName.${sourceSet.name}")
                )
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

    @Serializable
    private data class KotlinCompilerSettings(
        val jvmTarget: String?,
        val pluginOptions: List<String>,
        val pluginClasspaths: List<String>
    )
}
