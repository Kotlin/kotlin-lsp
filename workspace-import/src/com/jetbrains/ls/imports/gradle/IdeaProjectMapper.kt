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
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.containsKey
import kotlin.io.path.exists

private const val JAVA_ENABLE_PREVIEW_PROPERTY: String = "--enable-preview"

internal class IdeaProjectMapper {

    private val LOG = logger<IdeaProjectMapper>()
    private val projectJdkCache: MutableMap<String, SdkData?> = mutableMapOf()
    private val projectJavaLanguageLevel: MutableMap<String, String?> = mutableMapOf()

    fun toWorkspaceData(metadata: ProjectMetadata, buildRoot: Path): WorkspaceData {
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
                    buildRoot = buildRoot,
                    dependencyResolver = dependencyResolver,
                    contentRootResolver = contentRootResolver,
                    javaSettingsConsumer = { moduleJavaSettings -> javaSettings.add(moduleJavaSettings) },
                    sdkConsumer = { sdk -> sdks.add(sdk) }
                )
            }
            .forEach { modules.putAll(it) }

        val projectJdks = projectJdkCache.values
            .filterNotNull()
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
                Json.encodeToString(
                    KotlinCompilerSettings(
                        it.languageVersion,
                        it.jvmTarget,
                        it.pluginOptions,
                        it.pluginClasspaths
                    )
                )
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
        buildRoot: Path,
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
        val projectDirectory = module.gradleProject.projectDirectory.path
        val projectId = gradleProjectIdentityPath(module, buildRoot)
        modules[module.name] = ModuleData(
            name = module.name,
            dependencies = listOf(
                DependencyData.ModuleSource,
                sdkDependencyData
            ),
            contentRoots = listOf(
                ContentRootData(projectDirectory)
            ),
            externalProjectPath = projectDirectory,
            externalProjectId = projectId,
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

            // The project's published coordinate goes on its source-set modules (a dependency on this project
            // resolves to its `.main` module), with a `:test` classifier for test source sets — mirroring the
            // Maven importer. The non-aggregating root module deliberately gets no coordinate.
            val baseCoordinate = metadata.moduleCoordinates[module.name]
            val coordinate = when {
                baseCoordinate == null -> null
                sourceSet.isTest() -> "$baseCoordinate:test"
                else -> baseCoordinate
            }
            modules["${module.name}.${sourceSet.name}"] = ModuleData(
                name = "${module.name}.${sourceSet.name}",
                coordinate = coordinate,
                dependencies = sourceSetDependencies,
                contentRoots = contentRootResolver.getContentRoots(module, sourceSet),
                // The source-set module's content root is a source directory (e.g. src/main); a run should use the
                // owning subproject directory as its working directory instead.
                externalProjectPath = projectDirectory,
                // The source sets of a project all belong to that same Gradle project, so they share its path.
                externalProjectId = projectId,
            )
            val sourceSetJavaSettings = getModuleJavaSettingsData(module, projectJavaLevel, sourceSet)
            moduleJavaSettings.addIfNotNull(sourceSetJavaSettings)
        }

        // special case for the root project module
        if (projectJavaLanguageLevel.containsKey(module.name)) {
            val projectJavaSettings = getJavaSettingsData(
                module = module,
                moduleName = module.name,
                targetJavaVersion = projectJavaLevel
            )
            moduleJavaSettings.add(projectJavaSettings)
        } else {
            val rootModuleJavaSettings = moduleJavaSettings
                .mapNotNull { it.languageLevelId }
                .minByOrNull { com.intellij.util.lang.JavaVersion.parse(it) }
                ?.replace("JDK_", "")
                ?.replace("_PREVIEW", "") ?: projectJavaLevel
            val projectJavaSettings = getJavaSettingsData(
                module = module,
                moduleName = module.name,
                targetJavaVersion = rootModuleJavaSettings
            )
            moduleJavaSettings.add(projectJavaSettings)
        }

        moduleJavaSettings.forEach { javaSettingsConsumer(it) }
        return modules
    }

    private fun ModuleData.resolveSiblingName(mame: String): String {
        return name.split(".").dropLast(1).joinToString(".") + "." + mame
    }

    /**
     * [module]'s Gradle *identity path* — the path that identifies its project across the whole build tree, and the
     * one anything invoking a task on it has to use (`":"` for the root project, `":app"` for a subproject,
     * `":included"` / `":included:lib"` for a project of an included build).
     *
     * Asked of Gradle first, via `GradleProject.getBuildTreePath()`: the build tree path *is* the identity path, and
     * it is the only way to get one for a project of an included build. `getPath()` cannot serve, because it is
     * build-local — an included build's root project also reports `":"`, so recording it would give two projects in
     * one tree the same id and send a launch to whichever the consumer matched first.
     *
     * That method is `@Incubating` and `@since` Gradle 9.2.0, so it throws [UnsupportedMethodException] against an
     * older daemon (this importer supports back to 6.0.1). The fallback is the build-local derivation this used to do
     * alone: a path for the projects of the build that was imported, and `null` for an included build's, since the
     * build name is not derivable from the models requested here. `null` means a consumer launches the module
     * directly instead of through Gradle — the pre-9.2 behaviour, which is correct if less capable.
     */
    private fun gradleProjectIdentityPath(module: IdeaModule, buildRoot: Path): String? {
        gradleBuildTreePath(module)?.let { return it }
        val moduleBuildRoot = module.gradleProject.projectIdentifier?.buildIdentifier?.rootDir?.toPath() ?: return null
        if (!isSameDirectory(moduleBuildRoot, buildRoot)) {
            LOG.debug("${module.name} belongs to the included build at $moduleBuildRoot, not to $buildRoot")
            return null
        }
        return module.gradleProject.path?.takeIf { it.startsWith(":") }
    }

    /**
     * Whether [a] and [b] are the same directory, symlinks resolved.
     *
     * Compared by file identity rather than by text, because the two paths come from different places and need not be
     * spelled alike: Gradle reports its build root with symlinks already resolved, while the importer is invoked with
     * the path the client opened. A project reached through a symlinked directory — a macOS temp dir under `/tmp` or
     * `/var`, a symlinked home or checkout — therefore compared unequal, and every module of the build being imported
     * was written off as belonging to an *included* build: no identity path recorded, and so no launching or building
     * that module through Gradle. `normalize()` cannot fix that, since it resolves `.`/`..` but never a symlink.
     *
     * Only the pre-9.2 fallback path needs this; a daemon that answers `getBuildTreePath()` never gets here.
     */
    private fun isSameDirectory(a: Path, b: Path): Boolean =
        try {
            Files.isSameFile(a, b)
        }
        catch (e: IOException) {
            // One of them is gone (or unreadable), so there is no identity to compare; the textual comparison is the
            // best answer left, and it is the one this used to give unconditionally.
            LOG.debug("Cannot compare $a with $b by file identity; falling back to path comparison", e)
            a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
        }

    /**
     * [module]'s build tree path as Gradle reports it, or `null` when this daemon does not have the method (Gradle
     * older than 9.2.0) or answers with something that is not a Gradle path.
     *
     * The `startsWith(":")` check is the same one the [gradleProjectIdentityPath] fallback applies: a consumer builds
     * task paths out of this, so a value that is not a path has to be refused here rather than propagated. It is also
     * what makes the `@Incubating` status tolerable: should Gradle change the shape of this value, it stops being
     * recorded rather than being recorded wrongly, and the consumer falls back to a direct launch.
     */
    @Suppress("UnstableApiUsage") // `@Incubating`: see the KDoc — the fallback below is what covers it changing.
    private fun gradleBuildTreePath(module: IdeaModule): String? =
        try {
            module.gradleProject.buildTreePath?.takeIf { it.startsWith(":") }
        }
        catch (e: UnsupportedMethodException) {
            LOG.debug("GradleProject.getBuildTreePath() is unsupported by this Gradle; falling back for ${module.name}", e)
            null
        }

    private fun IdeaProject.getJavaLanguageLevel(projectMetadata: ProjectMetadata): String? {
        return languageLevel?.level?.replace("JDK_", "")
            ?: javaLanguageSettings?.languageLevel?.getJavaVersion()
            ?: calculateMinimalJavaLanguageLevelOfProject(projectMetadata)
    }

    private fun IdeaProject.calculateMinimalJavaLanguageLevelOfProject(projectMetadata: ProjectMetadata): String? {
        return modules
            .associate { it.javaLanguageSettings to (projectMetadata.sourceSets[it.name] ?: emptySet()) }
            .flatMap { javaLanguageToSourceSets ->
                val moduleSourceSets = javaLanguageToSourceSets.value
                val sourceSetCompatibility = moduleSourceSets.mapNotNull { it.javaSettings.sourceCompatibility }
                    .map { com.intellij.util.lang.JavaVersion.parse(it) }
                if (!sourceSetCompatibility.isEmpty()) {
                    return@flatMap sourceSetCompatibility
                }
                val javaSettings = javaLanguageToSourceSets.key?.languageLevel?.getJavaVersion()
                if (javaSettings != null) {
                    return@flatMap listOf(com.intellij.util.lang.JavaVersion.parse(javaSettings))
                }
                return@flatMap emptyList()
            }.minOrNull()?.toString()
    }

    private fun ModuleData.hasValidSourceRoots(): Boolean {
        return contentRoots
            .flatMap { it.sourceRoots }
            .any { Path.of(it.path).exists() }
    }

    private fun getModuleJavaSettingsData(
        module: IdeaModule,
        projectJavaLevel: String?,
        sourceSet: ModuleSourceSet?
    ): JavaSettingsData {
        // A Gradle source set can have more than one output directory (e.g. `build/classes/java/main`,
        // `build/classes/kotlin/main`, `build/resources/main`); they are collected here and sorted for a
        // deterministic order. The workspace model keeps only the first as the module's `compilerOutput`, so the
        // rest overflow to a side entity (see conversion.kt) — but all are put on the run classpath.
        val compilerOutputs = sourceSet?.outputDirs.orEmpty().map { it.path }
            .sorted()
            .ifEmpty { listOfNotNull(module.compilerOutput?.outputDir?.path) }
        val languageLevel = module.getLanguageLevel(projectJavaLevel, sourceSet)
        val compilerOptions = sourceSet?.javaSettings?.compileOptions?.toList() ?: emptyList()

        val moduleName = if (sourceSet == null) module.name else "${module.name}.${sourceSet.name}"
        return getJavaSettingsData(
            moduleName,
            module,
            languageLevel,
            compilerOutputs,
            compilerOptions
        )
    }

    private fun IdeaModule.getLanguageLevel(
        projectJavaLevel: String?,
        sourceSet: ModuleSourceSet?,
    ): String? {
        // project java settings should be used for the buildSrc project
        if (name.contains("buildSrc")) {
            if (project.javaLanguageSettings.isSpecified()) {
                return project.javaLanguageSettings
                    ?.targetBytecodeVersion
                    ?.getJavaVersion() ?: projectJavaLevel
            }
            return projectJavaLevel
        }
        val moduleJavaLevel = when {
            sourceSet.isToolchainSpecified() -> sourceSet?.javaSettings?.toolchainVersion.toString()
            sourceSet.isCompileTaskSpecified() -> sourceSet?.javaSettings?.targetCompatibility ?: sourceSet?.javaSettings?.sourceCompatibility
            javaLanguageSettings.isSpecified() -> javaLanguageSettings?.targetBytecodeVersion?.getJavaVersion()
            else -> null
        }
        val moduleJavaLevelSuffix = if (sourceSet?.javaSettings?.compileOptions?.contains(JAVA_ENABLE_PREVIEW_PROPERTY) == true) {
            "_PREVIEW"
        } else {
            ""
        }
        if (moduleJavaLevel != null) {
            return moduleJavaLevel + moduleJavaLevelSuffix
        }
        return projectJavaLevel
    }

    private fun ModuleSourceSet?.isToolchainSpecified(): Boolean {
        return this != null && javaSettings.toolchainVersion != null
    }

    private fun ModuleSourceSet?.isCompileTaskSpecified(): Boolean {
        return this != null && (javaSettings.sourceCompatibility != null || javaSettings.targetCompatibility != null)
    }

    private fun JavaVersion.getJavaVersion(): String {
        return name.replace("VERSION_", "")
            .replace("_", ".")
    }

    private fun getJavaSettingsData(
        moduleName: String,
        module: IdeaModule,
        targetJavaVersion: String?,
        compilerOutputs: List<String> = emptyList(),
        compilerArguments: List<String> = emptyList(),
    ): JavaSettingsData =
        JavaSettingsData(
            module = moduleName,
            inheritedCompilerOutput = module.compilerOutput?.inheritOutputDirs ?: false,
            compilerOutputs = compilerOutputs,
            compilerOutputsForTests = listOfNotNull(module.compilerOutput?.testOutputDir?.path),
            languageLevelId = targetJavaVersion?.let { "JDK_$it" },
            manifestAttributes = emptyMap(),
            excludeOutput = false,
            compilerArguments = compilerArguments
        )

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

    /**
     * When updating, see also [com.jetbrains.ls.imports.maven.KotlinJvmCompilerArguments].
     */
    @Serializable
    private data class KotlinCompilerSettings(
        val languageVersion: String?,
        val jvmTarget: String?,
        val pluginOptions: List<String>,
        val pluginClasspaths: List<String>
    )
}
