// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.containers.addIfNotNull
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.JavaSettingsData
import com.jetbrains.ls.imports.json.KotlinSettingsData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.WorkspaceData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.JavaVersion
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import java.nio.file.Path
import kotlin.io.path.exists

internal class IdeaProjectMapper {

    private val LOG = logger<IdeaProjectMapper>()
    private val projectJdkCache: MutableMap<String, SdkData?> = mutableMapOf()
    private val projectJavaLanguageLevel: MutableMap<String, String?> = mutableMapOf()

    fun toWorkspaceData(metadata: ProjectMetadata): WorkspaceData {
        val sdks: MutableList<SdkData> = mutableListOf()
        val javaSettings: MutableList<JavaSettingsData> = mutableListOf()

        fillProjectJdkCache(metadata.includedProjects)
        val dependencyResolver = SourceSetDependencyResolver(metadata)
        val contentRootResolver = GradleContentRootResolver(metadata)

        val modules = mutableMapOf<String, ModuleData>()
        metadata.includedProjects.flatMap { it.modules }
            .map { module ->
                splitModulePerSourceSet(
                    module = module,
                    metadata = metadata,
                    dependencyResolver = dependencyResolver,
                    contentRootResolver = contentRootResolver,
                    javaSettingsConsumer = { moduleJavaSettings -> javaSettings.add(moduleJavaSettings) },
                    sdkConsumer = { sdk -> sdks.add(sdk) }
                )
            }
            .forEach { modules.putAll(it) }

        val projectJdks = projectJdkCache.values
            .filterNotNull()
            .distinctBy { it.name }
        sdks.addAll(projectJdks)

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

    private fun calculateKotlinSettings(
        modules: Map<String, ModuleData>,
        kotlinModules: Map<String, KotlinModule>,
        sourceSets: Map<String, Set<ModuleSourceSet>>
    ): List<KotlinSettingsData> {

        data class SourceSetInfo(
            val parentModuleName: String,
            val moduleSourceSet: ModuleSourceSet,
        )

        /* Index source sets by their module 'fqn' */
        val sourceSetFqnIndex = buildMap {
            sourceSets.forEach { (parentModuleName, sourceSets) ->
                sourceSets.forEach { sourceSet ->
                    put("$parentModuleName.${sourceSet.name}", SourceSetInfo(parentModuleName, sourceSet))
                }
            }
        }

        val result = mutableListOf<KotlinSettingsData>()
        for ((name, moduleData) in modules) {
            if (!moduleData.hasValidSourceRoots()) {
                continue
            }
            val kotlinModuleKey = sourceSetFqnIndex[name]?.parentModuleName ?: name
            val kotlinModule = sourceSetFqnIndex[name]?.moduleSourceSet?.kotlinModule ?: kotlinModules[kotlinModuleKey]
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
                    additionalVisibleModuleNames = sourceSetFqnIndex[name]?.moduleSourceSet?.friendSourceSets.orEmpty()
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
        contentRootResolver: GradleContentRootResolver,
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
        val moduleJavaSettings: MutableList<JavaSettingsData> = mutableListOf()
        val projectJavaLevel = projectJavaLanguageLevel.computeIfAbsent(module.project.name) {
            module.project.getJavaLanguageLevel(metadata)
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
                contentRoots = contentRootResolver.getContentRoots(module, sourceSet)
            )
            val sourceSetJavaSettings = getModuleJavaSettingsData(
                "${module.name}.${sourceSet.name}",
                module,
                projectJavaLevel,
                sourceSet
            )
            moduleJavaSettings.addIfNotNull(sourceSetJavaSettings)
        }
        val rootModuleJavaSettings = moduleJavaSettings
            .filter { it.languageLevelId != null }
            .minByOrNull { com.intellij.util.lang.JavaVersion.parse(it.languageLevelId!!) }
        if (rootModuleJavaSettings != null) {
            moduleJavaSettings.add(rootModuleJavaSettings.copy(module = module.name))
        } else {
            moduleJavaSettings.addIfNotNull(getModuleJavaSettingsData(module.name, module, projectJavaLevel, null))
        }
        moduleJavaSettings.forEach { javaSettingsConsumer(it) }
        return modules
    }

    private fun ModuleData.resolveSiblingName(mame: String): String {
        return name.split(".").dropLast(1).joinToString(".") + "." + mame
    }

    private fun IdeaProject.getJavaLanguageLevel(projectMetadata: ProjectMetadata): String? {
        val mayBeJavaLevel = modules
            .associate { it.javaLanguageSettings to (projectMetadata.sourceSets[it.name] ?: emptySet()) }
            .flatMap { javaLanguageToSourceSets ->
                val moduleSourceSets = javaLanguageToSourceSets.value
                val sourceSetCompatibility = moduleSourceSets.mapNotNull { it.sourceCompatibility }
                    .map { com.intellij.util.lang.JavaVersion.parse(it) }
                if (!sourceSetCompatibility.isEmpty()) {
                    return@flatMap sourceSetCompatibility
                }
                val javaSettings = javaLanguageToSourceSets.key?.languageLevel?.getJavaVersion()
                if (javaSettings != null) {
                    return@flatMap listOf(com.intellij.util.lang.JavaVersion.parse(javaSettings))
                }
                return@flatMap emptyList()
            }.minOrNull()
        if (mayBeJavaLevel != null) {
            return mayBeJavaLevel.toString()
        }
        return languageLevel?.level?.replace("JDK_", "")
            ?: javaLanguageSettings?.languageLevel?.getJavaVersion()
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
        if (module.name.contains("buildSrc") && module.project.javaLanguageSettings.isSpecified()) {
            val targetJavaVersion = module.project.javaLanguageSettings
                ?.targetBytecodeVersion
                ?.getJavaVersion()
            if (targetJavaVersion != null) {
                return getJavaSettingsData(moduleName, module, targetJavaVersion)
            }
        }
        val targetJavaVersion = when {
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
