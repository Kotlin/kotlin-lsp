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
import org.gradle.api.JavaVersion
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

internal class IdeaProjectMapper {

    private val LOG = logger<IdeaProjectMapper>()
    private val projectJdkCache: MutableMap<String, SdkData?> = mutableMapOf()
    private val projectJavaLanguageLevel: MutableMap<String, String?> = mutableMapOf()

    fun toWorkspaceData(metadata: ProjectMetadata): WorkspaceData {
        val sdks: MutableList<SdkData> = mutableListOf()
        val javaSettings: MutableList<JavaSettingsData> = mutableListOf()
        val modules = mutableMapOf<String, ModuleData>()

        val allGradleModules: List<IdeaModule> = metadata.includedProjects.flatMap { it.modules }
        val dependencyResolver = SourceSetDependencyResolver(metadata)
        fillProjectJdkCache(metadata.includedProjects)

        allGradleModules
            .map {
                splitModulePerSourceSet(
                    module = it,
                    metadata = metadata,
                    dependencyResolver = dependencyResolver,
                    javaSettingsConsumer = { moduleJavaSettings -> javaSettings.add(moduleJavaSettings) },
                    sdkConsumer = { sdk -> sdks.add(sdk) }
                )
            }
            .forEach { modules.putAll(it) }

        sdks.addAll(projectJdkCache.values.filterNotNull())

        return WorkspaceData(
            modules = modules.values.toList(),
            libraries = dependencyResolver.getProjectLibraries(),
            sdks = sdks,
            javaSettings = javaSettings,
            kotlinSettings = calculateKotlinSettings(modules, metadata.kotlinModules, metadata.sourceSets)
        )
    }

    private fun fillProjectJdkCache(includedProjects: List<IdeaProject>) {
        for (project in includedProjects) {
            projectJdkCache[project.name] = project.getProjectJdk()
        }
    }

    private val mainSourceSetSuffix: String = ".main"
    private val testSourceSetSuffix: String = ".test"

    private fun calculateKotlinSettings(
        modules: Map<String, ModuleData>,
        kotlinModules: Map<String, KotlinModule>,
        sourceSets: Map<String, Set<ModuleSourceSet>>
    ): List<KotlinSettingsData> {

        /* Index source sets by their module 'fqn' */
        val sourceSetFqnIndex = buildMap {
            sourceSets.forEach { (name, sourceSets) ->
                sourceSets.forEach { sourceSet ->
                    put("$name.${sourceSet.name}", sourceSet)
                }
            }
        }

        val result = mutableListOf<KotlinSettingsData>()
        for ((name, moduleData) in modules) {
            if (!moduleData.hasValidSourceRoots()) {
                continue
            }
            val kotlinModuleKey = name.removeSuffix(mainSourceSetSuffix).removeSuffix(testSourceSetSuffix)
            val kotlinModule = sourceSetFqnIndex[name]?.kotlinModule ?: kotlinModules[kotlinModuleKey]
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
                    additionalVisibleModuleNames = sourceSetFqnIndex[name]?.friendSourceSets.orEmpty()
                        .map { friendModuleName -> moduleData.resolveSiblingName(friendModuleName) }
                        .toSet(),
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
        dependencyResolver: SourceSetDependencyResolver,
        javaSettingsConsumer: (JavaSettingsData) -> Unit,
        sdkConsumer: (SdkData) -> Unit
    ): Map<String, ModuleData> {
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
        modules[module.name] = ModuleData(
            name = module.name,
            dependencies = listOf(
                DependencyData.ModuleSource,
                sdkDependencyData
            ),
            contentRoots = listOf(
                ContentRootData(module.gradleProject.projectDirectory.path)
            )
        )
        val associatedSourceSets = metadata.sourceSets[module.name]
        if (associatedSourceSets.isNullOrEmpty()) {
            LOG.info("${module.name} has an empty set of source sets")
            return modules
        }
        val projectJavaLevel = projectJavaLanguageLevel.computeIfAbsent(module.project.name) {
            module.project.getJavaLanguageLevel()
        }
        val moduleJavaSettings = getModuleJavaSettingsData(module.name, module, projectJavaLevel, null)
        if (moduleJavaSettings != null) {
            javaSettingsConsumer(moduleJavaSettings)
        }
        associatedSourceSets.forEach { sourceSet ->
            val sourceSetDependencies = mutableListOf<DependencyData>()
                .apply {
                    if (sourceSet.hasUnresolvedDependencies()) {
                        addAll(dependencyResolver.resolveDependenciesFromIdeaModule(module, sourceSet))
                    } else {
                        addAll(dependencyResolver.resolveDependencies(module.name, sourceSet))
                    }
                    add(DependencyData.ModuleSource)
                    add(sdkDependencyData)
                }

            modules["${module.name}.${sourceSet.name}"] = ModuleData(
                name = "${module.name}.${sourceSet.name}",
                dependencies = sourceSetDependencies,
                contentRoots = sourceSet.toContentRootData(
                    module.gradleProject.projectDirectory,
                    sourceSet.isTest()
                )
            )
            val sourceSetJavaSettings = getModuleJavaSettingsData(
                "${module.name}.${sourceSet.name}",
                module,
                projectJavaLevel,
                sourceSet
            )
            if (sourceSetJavaSettings != null) {
                javaSettingsConsumer(sourceSetJavaSettings)
            }
        }
        return modules
    }

    private fun ModuleData.resolveSiblingName(mame: String): String {
        return name.split(".").dropLast(1).joinToString(".") + "." + mame
    }

    private fun IdeaProject.getJavaLanguageLevel(): String? {
        return languageLevel?.level?.replace("JDK_", "")
            ?: javaLanguageSettings?.languageLevel?.getJavaVersion()
    }

    private fun ModuleSourceSet.toContentRootData(moduleRoot: File, isTest: Boolean): List<ContentRootData> {
        val sourceRoots = mutableListOf<SourceRootData>()
        for (sourceRootFolder in sources) {
            if (sourceRootFolder.exists() && sourceRootFolder.isDirectory) {
                sourceRoots.add(
                    SourceRootData(
                        sourceRootFolder.path,
                        if (isTest) "java-test" else "java-source"
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
        return listOf(
            ContentRootData(
                findRootForSourceRoots(name, moduleRoot, sourceRoots),
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

    private fun findRootForSourceRoots(sourceSetName: String, moduleRoot: File, sourceRoots: List<SourceRootData>): String {
        if (sourceRoots.isEmpty() || sourceRoots.size == 1) {
            return "${moduleRoot.path}/src/$sourceSetName"
        }
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

    private fun getModuleJavaSettingsData(
        moduleName: String,
        module: IdeaModule,
        projectJavaLevel: String?,
        sourceSet: ModuleSourceSet?
    ): JavaSettingsData? {
        // project java settings should be used for the buildSrc project
        val targetJavaVersion = when {
            module.name.contains("buildSrc") && module.project.javaLanguageSettings.isSpecified() -> module.project.javaLanguageSettings
                ?.targetBytecodeVersion
                ?.getJavaVersion()

            sourceSet.isToolchainSpecified() -> sourceSet!!.toolchainVersion.toString()
            sourceSet.isCompileTaskSpecified() -> sourceSet!!.targetCompatibility ?: sourceSet.sourceCompatibility
            module.javaLanguageSettings.isSpecified() -> module.javaLanguageSettings?.targetBytecodeVersion?.getJavaVersion()
            else -> null
        }
        if (targetJavaVersion == projectJavaLevel) {
            return null
        }
        return getJavaSettingsData(moduleName, module, targetJavaVersion)
    }

    private fun ModuleSourceSet?.isToolchainSpecified(): Boolean {
        if (this == null) {
            return false
        }
        return toolchainVersion != null
    }

    private fun ModuleSourceSet?.isCompileTaskSpecified(): Boolean {
        if (this == null) {
            return false
        }
        return sourceCompatibility != null || targetCompatibility != null
    }

    private fun JavaVersion.getJavaVersion(): String {
        return name.replace("VERSION_", "")
            .replace("_", ".")
    }

    private fun getJavaSettingsData(moduleName: String, module: IdeaModule, targetJavaVersion: String?): JavaSettingsData? {
        if (targetJavaVersion == null) {
            return null
        }
        return JavaSettingsData(
            module = moduleName,
            inheritedCompilerOutput = module.compilerOutput?.inheritOutputDirs ?: false,
            compilerOutput = module.compilerOutput?.outputDir?.path,
            compilerOutputForTests = module.compilerOutput?.testOutputDir?.path,
            languageLevelId = "JDK_${targetJavaVersion}",
            manifestAttributes = emptyMap(),
            excludeOutput = false
        )
    }

    private fun IdeaJavaLanguageSettings?.isSpecified(): Boolean {
        return this != null && (jdk != null || languageLevel != null || targetBytecodeVersion != null)
    }

    private fun getSdkData(module: IdeaModule): SdkData? {
        return if (module.javaLanguageSettings.isSpecified()) {
            val jdkSettings = module.javaLanguageSettings?.jdk ?: return null
            val projectJdk = projectJdkCache.computeIfAbsent(module.project.name) { module.project.getProjectJdk() }
            if (jdkSettings.javaVersion.name == projectJdk?.name) {
                return null
            }
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

    private fun IdeaProject.getProjectJdk(): SdkData {
        return SdkData(
            name = jdkName,
            type = "jdk",
            homePath = javaLanguageSettings?.jdk?.javaHome?.path,
            version = javaLanguageSettings?.jdk?.javaVersion?.majorVersion?.let { "JDK_$it" },
            additionalData = ""
        )
    }

    @Serializable
    private data class KotlinCompilerSettings(
        val jvmTarget: String?,
        val pluginOptions: List<String>,
        val pluginClasspaths: List<String>
    )
}
