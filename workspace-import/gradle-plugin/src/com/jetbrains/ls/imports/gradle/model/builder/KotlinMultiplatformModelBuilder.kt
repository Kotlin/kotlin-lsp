// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.builder

import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformCompilation
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformSourceSet
import com.jetbrains.ls.imports.gradle.model.impl.KotlinMultiplatformCompilationImpl
import com.jetbrains.ls.imports.gradle.model.impl.KotlinMultiplatformLanguageSettingsImpl
import com.jetbrains.ls.imports.gradle.model.impl.KotlinMultiplatformModelImpl
import com.jetbrains.ls.imports.gradle.model.impl.KotlinMultiplatformSourceSetImpl
import com.jetbrains.ls.imports.gradle.utils.IdeCompilerArgumentsResolverReflection
import com.jetbrains.ls.imports.gradle.utils.IdeMultiplatformImportReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinMppCompilationReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinMultiplatformExtensionReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinMppSourceSetReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinMppTargetReflection
import com.jetbrains.ls.imports.gradle.utils.androidComponents
import com.jetbrains.ls.imports.gradle.utils.archiveFileOrNull
import com.jetbrains.ls.imports.gradle.utils.kotlinDestinationDirOrNull
import com.jetbrains.ls.imports.gradle.utils.kotlinMultiplatformExtension
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.util.GradleVersion
import java.io.File

private val TARGET_MODEL_NAME: String = KotlinMultiplatformModel::class.java.name

/**
 * The `org.jetbrains.kotlin.idea.projectModel.KotlinPlatform` ids of the IntelliJ Kotlin plugin. They equal the
 * `org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType` names of KGP.
 */
private val KNOWN_PLATFORMS = setOf("common", "jvm", "androidJvm", "js", "wasm", "native")
private const val COMMON_PLATFORM = "common"
private const val COMMON_MAIN = "commonMain"
private const val COMMON_TEST = "commonTest"

/**
 * Builds [com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel] for a project with the `kotlin` multiplatform
 * extension, with the same source sets, compilations, platforms and test detection as the IDE.
 *
 * Dependencies are not resolved here. KGP's `org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport` resolves
 * them, as the IDE does for every KGP that has it.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder`
 */
class KotlinMultiplatformModelBuilder : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean = TARGET_MODEL_NAME == modelName

    override fun buildAll(modelName: String, project: Project): KotlinMultiplatformModel? {
        val kotlin = kotlinMultiplatformExtension(project) ?: return null
        // No targets, no model: as `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder.shouldBuild`.
        if (kotlin.targets.isEmpty()) return null
        try {
            return buildModel(project, kotlin)
        }
        catch (e: Exception) {
            // A broken KMP model must not fail the whole sync: the JVM source sets are still imported.
            project.logger.error("[LSP Kotlin Multiplatform import] Failed to build the model of ${project.path}", e)
            return null
        }
    }

    private fun buildModel(project: Project, kotlin: KotlinMultiplatformExtensionReflection): KotlinMultiplatformModel {
        val importReflection = IdeMultiplatformImportReflection.create(kotlin)
        if (importReflection == null) {
            project.logger.warn("[LSP Kotlin Multiplatform import] KGP of ${project.path} has no IdeMultiplatformImport; dependencies are not imported")
        }
        val argumentsResolver = IdeCompilerArgumentsResolverReflection.create(kotlin)

        val sourceSetsByName = kotlin.sourceSets.associateBy { it.name }
        // The `metadata` target only packs the shared source sets into metadata klibs for consumers. The shared source
        // sets become modules of their own, with the precise platforms of the leaf compilations, so the IDE skips it
        // too (`org.jetbrains.kotlin.idea.gradleTooling.builders.KotlinTargetBuilder`,
        // `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`).
        val targets = kotlin.targets.filter { !it.isMetadataTarget && it.platformType in KNOWN_PLATFORMS }
        val compilations = targets.flatMap { target ->
            target.compilations.mapNotNull { compilation -> buildCompilation(project, target, compilation, sourceSetsByName, argumentsResolver) }
        }
        val coerceRootSourceSetsToCommon = project.readBooleanProperty("kotlin.mpp.coerceRootSourceSetsToCommon") ?: true
        val projectPlatforms = targets.mapNotNull { it.platformType }.toSet()
        val allTargetsManagedByAndroidPlugin = targets.all { it.isKotlinAndroidTarget }

        // A source set takes part in a compilation if the compilation includes it or a source set that depends on it.
        val compilationsBySourceSet = HashMap<String, MutableList<KotlinMultiplatformCompilation>>()
        for (compilation in compilations) {
            for (sourceSetName in compilation.allSourceSets) {
                compilationsBySourceSet.getOrPut(sourceSetName) { ArrayList() } += compilation
            }
        }

        val sourceSets = kotlin.sourceSets.mapNotNull { sourceSet ->
            val participatedCompilations = compilationsBySourceSet[sourceSet.name]
            buildSourceSet(
                sourceSet = sourceSet,
                allDependsOn = dependsOnClosure(setOf(sourceSet.name), sourceSetsByName),
                compilations = participatedCompilations,
                platforms = when {
                    // Orphan source sets get the platforms that the project uses, as in
                    // `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder.computeSourceSetPlatforms`.
                    participatedCompilations == null -> projectPlatforms
                    // Only the root source sets are coerced to common.
                    // `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder.shouldCoerceToCommon` runs
                    // before the platforms are set, so its single-platform shortcut never applies.
                    coerceRootSourceSetsToCommon && sourceSet.name in setOf(COMMON_MAIN, COMMON_TEST) ->
                        setOf(COMMON_PLATFORM)
                    else -> participatedCompilations.map { it.platform }.toSet()
                },
                isManagedByAndroidPlugin = participatedCompilations?.all { it.isManagedByAndroidPlugin } ?: allTargetsManagedByAndroidPlugin,
            )
        }

        // Orphan source sets are resolved too: the Kotlin plugin imports them (`import_orphan_source_sets` defaults to true).
        val dependencies = if (importReflection == null) emptyMap()
        else sourceSets.associate { sourceSet -> sourceSet.name to importReflection.resolveDependenciesSerialized(sourceSet.name) }

        return KotlinMultiplatformModelImpl(
            projectIdentityPath = project.identityPath(),
            kotlinGradlePluginVersion = kotlin.kotlinPluginVersion,
            sourceSets = sourceSets,
            compilations = compilations,
            dependencies = dependencies,
            // Only the Android-KMP library plugin: the legacy `androidTarget()` belongs to the Android import, which adds
            // the SDK itself.
            androidBootClasspath = if (targets.any { it.platformType == "androidJvm" && !it.isKotlinAndroidTarget }) project.androidBootClasspath()
            else emptyList(),
        )
    }

    /** The boot classpath from the `androidComponents` extension, which the Android-KMP library plugin also registers. */
    private fun Project.androidBootClasspath(): List<File> =
        try {
            androidComponents?.sdkComponents?.bootClasspath?.get().orEmpty().map { it.asFile }
        }
        catch (e: Exception) {
            // A missing SDK must not fail the model: the Android modules then lack only the platform classes.
            logger.error("[LSP Kotlin Multiplatform import] Failed to resolve the Android boot classpath of $path", e)
            emptyList()
        }

    /**
     * A compilation without a compile task is skipped.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.builders.KotlinCompilationBuilder.buildComponent`
     */
    private fun buildCompilation(
        project: Project,
        target: KotlinMppTargetReflection,
        compilation: KotlinMppCompilationReflection,
        sourceSetsByName: Map<String, KotlinMppSourceSetReflection>,
        argumentsResolver: IdeCompilerArgumentsResolverReflection?,
    ): KotlinMultiplatformCompilation? {
        val compileTask = compilation.compileKotlinTaskName?.let { project.tasks.findByName(it) } ?: return null
        val platform = target.platformType ?: return null
        val declaredSourceSets = compilation.sourceSets.map { it.name }.filter { it in sourceSetsByName }.toSet()
        val outputDirs = LinkedHashSet<File>().apply {
            addAll(compilation.classesDirs)
            compileTask.kotlinDestinationDirOrNull()?.let(::add)
            compilation.resourcesDir?.let(::add)
        }
        return KotlinMultiplatformCompilationImpl(
            fullName = compilationFullName(compilation.name, target.disambiguationClassifier),
            platform = platform,
            konanTarget = compilation.konanTargetName,
            wasmTarget = compilation.wasmTargetName ?: when (target.presetName) {
                // The compilation can have no Wasm target. The preset of the target gives it then, as in
                // `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`. The IDE does this
                // only for KGP 2.0 and later. This code does it for every KGP.
                // Before KGP 2.0 this probably breaks a `wasmWasi` module. It then does not see the klibs that have no
                // `wasm_targets` in the manifest.
                "wasmJs" -> "wasm-js"
                "wasmWasi" -> "wasm-wasi"
                else -> null
            }.takeIf { platform == "wasm" },
            declaredSourceSets = declaredSourceSets,
            allSourceSets = declaredSourceSets + dependsOnClosure(declaredSourceSets, sourceSetsByName),
            compilerArguments = argumentsResolver?.resolveCompilerArguments(compileTask),
            outputDirs = outputDirs,
            archiveFile = compilation.archiveTaskName?.let { project.tasks.findByName(it) }?.archiveFileOrNull(),
            // As in `org.jetbrains.kotlin.idea.gradleTooling.builders.KotlinCompilationBuilder.buildComponents`, a test compilation has an
            // associated compilation. This holds when `kotlin.mpp.import.legacyTestSourceSetDetection` is off, which is
            // the default. This code does not read that property.
            isTest = compilation.associateCompilations.isNotEmpty(),
            isManagedByAndroidPlugin = target.isKotlinAndroidTarget,
        )
    }

    /**
     * A source set without language settings is skipped.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.builders.KotlinSourceSetBuilder.buildKotlinSourceSet`
     */
    private fun buildSourceSet(
        sourceSet: KotlinMppSourceSetReflection,
        allDependsOn: Set<String>,
        compilations: List<KotlinMultiplatformCompilation>?,
        platforms: Set<String>,
        isManagedByAndroidPlugin: Boolean,
    ): KotlinMultiplatformSourceSet? {
        val languageSettings = sourceSet.languageSettings ?: return null
        return KotlinMultiplatformSourceSetImpl(
            name = sourceSet.name,
            sourceDirs = sourceSet.kotlin?.srcDirs.orEmpty(),
            resourceDirs = sourceSet.resources?.srcDirs.orEmpty(),
            generatedSourceDirs = sourceSet.generatedKotlin?.srcDirs.orEmpty(),
            declaredDependsOn = sourceSet.dependsOn,
            allDependsOn = allDependsOn,
            additionalVisible = sourceSet.additionalVisibleSourceSets,
            platforms = platforms,
            isTest = compilations?.all { it.isTest } ?: false,
            isManagedByAndroidPlugin = isManagedByAndroidPlugin,
            languageSettings = KotlinMultiplatformLanguageSettingsImpl(
                languageVersion = languageSettings.languageVersion,
                apiVersion = languageSettings.apiVersion,
                progressiveMode = languageSettings.progressiveMode,
                enabledLanguageFeatures = languageSettings.enabledLanguageFeatures,
                optInAnnotationsInUse = languageSettings.optInAnnotationsInUse,
                compilerPluginArguments = languageSettings.compilerPluginArguments,
                compilerPluginClasspath = languageSettings.compilerPluginClasspath,
                freeCompilerArgs = languageSettings.freeCompilerArgs,
            ),
        )
    }

    /** The source sets that [roots] depend on transitively, without [roots] themselves. */
    private fun dependsOnClosure(roots: Set<String>, sourceSetsByName: Map<String, KotlinMppSourceSetReflection>): Set<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque(roots.flatMap { sourceSetsByName[it]?.dependsOn.orEmpty() })
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in roots || !result.add(name)) continue
            queue.addAll(sourceSetsByName[name]?.dependsOn.orEmpty())
        }
        return result
    }

    /**
     * `jvm` + `main` gives `jvmMain`, the compilation's default source set.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.compilationFullName`
     */
    private fun compilationFullName(name: String, classifier: String?): String =
        if (classifier == null) name else classifier + name.replaceFirstChar { it.uppercaseChar() }

    /**
     * Reads a Boolean Gradle property: extra first, then gradle.properties.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.readProperty`
     */
    private fun Project.readBooleanProperty(name: String): Boolean? {
        return try {
            val extra = extensions.extraProperties
            (if (extra.has(name)) extra.get(name)?.toString() else null)?.toBoolean()
            ?: providers.gradleProperty(name).orNull?.toBoolean()
        }
        catch (e: Exception) {
            logger.error("[LSP Kotlin Multiplatform import] Failed to read property $name of $path", e)
            null
        }
    }

    /**
     * The identity path of the project in the build tree. [org.gradle.api.Project.getBuildTreePath] is public since
     * Gradle 8.3. Before that, the internal [org.gradle.api.internal.project.ProjectInternal.getIdentityPath] has the
     * same value. The last fallback, the build-local [org.gradle.api.Project.getPath], is wrong only for a project of
     * an included build.
     */
    private fun Project.identityPath(): String {
        if (GradleVersion.current() >= GradleVersion.version("8.3")) {
            @Suppress("UnstableApiUsage")
            return buildTreePath
        }
        return try {
            javaClass.getMethod("getIdentityPath").invoke(this).toString()
        }
        catch (_: Exception) {
            path
        }
    }
}
