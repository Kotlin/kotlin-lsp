// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.kmp

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.GradleContentRootResolver
import com.jetbrains.ls.imports.gradle.withKotlinRootTypes
import com.jetbrains.ls.imports.gradle.model.AndroidProject
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformCompilation
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformSourceSet
import com.jetbrains.ls.imports.gradle.util.DependencyFileIndex
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.KotlinSettingsData
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.ModuleData
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.serializeComponentPlatforms
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectArtifactDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinSourceDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinUnresolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.artifactsClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.extras.documentationClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeDistribution
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeStdlib
import org.jetbrains.kotlin.gradle.idea.tcs.extras.klibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.sourcesClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.isKotlinCompileBinaryType
import java.io.File

private val LOG = logger<KotlinMultiplatformMapper>()

private const val COMMON_MAIN = "commonMain"
private const val COMMON_TEST = "commonTest"

/**
 * The name of the Android SDK library of the AGP import
 * (`com.jetbrains.ls.imports.gradle.model.builder.android.resolveAndroidBootClasspathDependencies`), so both share it.
 */
private const val ANDROID_SDK_LIBRARY = "Gradle: android:android-sdk:null"

/** The modules of one Kotlin Multiplatform project, with their Kotlin settings and compile outputs. */
internal class KotlinMultiplatformModules(
    val modules: List<ModuleData>,
    val kotlinSettings: List<KotlinSettingsData>,
    /** Module name to its compile output directories. Only compilation modules have outputs. */
    val compilerOutputs: Map<String, List<String>>,
    /** The source set names of the project that the KMP modules replace in the Java source set import. */
    val sourceSetNames: Set<String>,
)

/**
 * Maps the Kotlin Multiplatform projects of a build to LSP modules the way the IDE maps them to IDE modules.
 *
 * A module is named `<project module>.<source set>`, as the Kotlin plugin's internal module name. Kotlin settings
 * refer to other modules by this name, because the analyzer resolves `dependsOnModuleNames` by module name
 * (`com.jetbrains.analyzer.kotlin.AnalyzerStableModuleNameProvider`).
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinMppGradleProjectResolver`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateModuleDependencies`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService`
 */
internal class KotlinMultiplatformMapper(
    /** Project module FQN to the KMP model of the project. */
    private val models: Map<String, KotlinMultiplatformModel>,
    /** Project module FQN to the AGP model of the project. */
    androidProjects: Map<String, AndroidProject>,
) {
    /**
     * Gradle identity path to the project module FQN, to resolve
     * `org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectCoordinates`.
     */
    private val moduleFqnByIdentityPath: Map<String, String> = models.entries.associate { (fqn, model) -> model.projectIdentityPath to fqn }

    /** Gradle build tree path to the module of the active variant of an AGP project. */
    private val androidModuleByBuildTreePath: Map<String, String> = androidProjects.entries.associate { (fqn, project) ->
        project.buildTreePath to "$fqn.${project.activeVariant}"
    }

    /**
     * The platforms of all KMP modules in the build.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.getProjectPlatforms`
     */
    private val projectPlatforms: Set<String> = models.values.flatMapTo(HashSet()) { model ->
        model.sourceSets.flatMap { it.platforms } + model.compilations.map { it.platform }
    }

    private val librariesByName = LinkedHashMap<String, LibraryData>()

    /**
     * The libraries of all KMP binary dependencies. They are project-level, as
     * `org.jetbrains.kotlin.gradle.idea.tcs.extras.isIdeaProjectLevel` defaults to true.
     */
    val libraries: Collection<LibraryData> get() = librariesByName.values

    /**
     * Registers the outputs and archives of each compilation in [index]. A consumer that has one of them on its
     * classpath depends on all source set modules of the compilation. A compilation archive also maps to all its source
     * sets.
     *
     * LSP port of IDE code: the `artifactsMap` of `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`
     */
    fun registerCompilationArtifacts(index: DependencyFileIndex) {
        for ((fqn, model) in models) {
            for (compilation in model.compilations) {
                if (compilation.isManagedByAndroidPlugin) continue
                registerArtifacts(index, fqn, model, compilation, compilation.outputDirs + listOfNotNull(compilation.archiveFile))
            }
        }
    }

    /**
     * Registers [files] as artifacts of the main Android compilation of the KMP project at [projectIdentityPath].
     * AGP reports a dependency on an Android-KMP library as a project artifact. Returns false if there is no such
     * project, so the caller keeps its own handling.
     */
    fun registerAndroidArtifacts(index: DependencyFileIndex, projectIdentityPath: String, files: Collection<File>): Boolean {
        val fqn = moduleFqnByIdentityPath[projectIdentityPath] ?: return false
        val model = models.getValue(fqn)
        val compilation = model.compilations.firstOrNull { it.platform == KotlinPlatformId.ANDROID_JVM && !it.isTest } ?: return false
        registerArtifacts(index, fqn, model, compilation, files)
        return true
    }

    private fun registerArtifacts(
        index: DependencyFileIndex,
        fqn: String,
        model: KotlinMultiplatformModel,
        compilation: KotlinMultiplatformCompilation,
        files: Collection<File>,
    ) {
        // The most specific source set goes first, as
        // `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.sourceSetOrderComparator` orders them.
        val sourceSetNames = listOf(compilation.fullName) + compilation.allSourceSets.sortedWith(dependsOnOrder(model))
        for (file in files) {
            for (sourceSetName in sourceSetNames.distinct()) {
                index.add(file, DependencyData.Module("$fqn.$sourceSetName", DependencyDataScope.RUNTIME, isTestJar = compilation.isTest))
            }
        }
    }

    /**
     * The modules of the KMP project [module], or null if it is not a KMP project.
     *
     * - Pass 1 creates a module for each compilation. Pass 2 creates a module for each source set that has no module yet.
     * - A compilation has the module id of its default source set: the full name of a compilation is the name of that
     *   source set. So pass 2 skips the default source set.
     * - The compilation module gets the content roots and the KGP dependencies of its default source set. Both are
     *   looked up by the module id of the source set.
     * - The dependencies come from KGP's `org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport`. Without it,
     *   this code emits no dependencies. The IDE fallback for that case is not ported.
     *
     * LSP port of IDE code:
     * - `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.createMppGradleSourceSetDataNodes`: the loop over the
     *   compilations of the targets (pass 1), then the loop over `sourceSetsByName` (pass 2)
     * - `org.jetbrains.kotlin.idea.gradleJava.configuration.utils.KotlinModuleUtils.getKotlinModuleId`: the module id
     *   from `fullName`
     * - `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateContentRoots`: the content roots by the module id
     * - `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateModuleDependenciesWithDependenciesContainer`:
     *   the dependencies by the module id
     * - `org.jetbrains.kotlin.idea.gradleTooling.builders.buildIdeaKotlinDependenciesContainer`: the KGP dependencies.
     *   The fallback is `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateModuleDependenciesWithoutDependenciesContainer`.
     */
    fun map(
        module: IdeaModule,
        contentRootResolver: GradleContentRootResolver,
        dependencyIndex: DependencyFileIndex,
        commonDependencies: List<DependencyData>,
        gradleProjectId: String?,
        javaSourceSetNames: Set<String>,
    ): KotlinMultiplatformModules? {
        val fqn = module.name
        val model = models[fqn] ?: return null
        val sourceSetsByName = model.sourceSets.associateBy { it.name }
        val dependencies = model.deserializeDependencies()

        val components = ArrayList<Component>()
        val compilationsByName = LinkedHashMap<String, KotlinMultiplatformCompilation>()
        for (compilation in model.compilations) {
            if (compilation.isManagedByAndroidPlugin) continue
            // One module per module id: the first compilation with this full name wins, as in pass 1.
            if (compilationsByName.putIfAbsent(compilation.fullName, compilation) != null) continue
            components += Component.Compilation(compilation, sourceSetsByName[compilation.fullName])
        }
        for (sourceSet in model.sourceSets) {
            if (sourceSet.isManagedByAndroidPlugin || sourceSet.name in compilationsByName) continue
            components += Component.SourceSet(sourceSet, compilationsOf(sourceSet.name, model))
        }

        val androidSdk = androidSdkDependency(fqn, model)
        val componentResults = components.associateWith { component ->
            val resolved = resolveDependencies(fqn, component, dependencies[component.name].orEmpty(), dependencyIndex)
            // Only a pure Android module gets the SDK, as the Android plugin gives only Android source sets the Android SDK.
            if (androidSdk == null || component.platforms != setOf(KotlinPlatformId.ANDROID_JVM)) resolved
            else ResolvedDependencies(resolved.dependencies + androidSdk, resolved.dependsOn, resolved.additionalVisible)
        }
        val compilerArguments = components.associateWith { it.compilerArguments() }

        val modules = ArrayList<ModuleData>()
        val kotlinSettings = ArrayList<KotlinSettingsData>()
        val compilerOutputs = LinkedHashMap<String, List<String>>()
        for (component in components) {
            val moduleName = "$fqn.${component.name}"
            val resolved = componentResults.getValue(component)
            val sourceSet = component.sourceSet
            val contentRoots = contentRootResolver.getContentRoots(
                module = module,
                sources = sourceSet?.let { it.sourceDirs + it.generatedSourceDirs }.orEmpty(),
                resources = sourceSet?.resourceDirs.orEmpty(),
                excludes = emptySet(),
                isTest = component.isTest,
                includeMissing = true,
            ).let { roots -> if (component.usesKotlinRootTypes()) roots.map { it.withKotlinRootTypes() } else roots }

            modules += ModuleData(
                name = moduleName,
                // KGP gives a flat, transitive list, which must stay complete: the analyzer does not follow the
                // dependencies of a dependency. A test module gets it in the test scope
                // (`org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.assignTestScope`).
                dependencies = resolved.dependencies.map { if (component.isTest) it.inTestScope() else it } + commonDependencies,
                contentRoots = contentRoots,
                externalProjectPath = module.gradleProject.projectDirectory.path,
                // The Gradle build (`<source set>Classes`) and launch work on the Java source set of this name, which the
                // launch looks up in `sourceSets`. A module that replaces one (`jvmMain`) keeps the Gradle project that it had
                // before the KMP import. The other modules have no Java source set, so they are launched directly.
                externalProjectId = gradleProjectId.takeIf { component.name in javaSourceSetNames },
            )
            // Only a compilation writes classes. Its outputs go on the run classpath of the module and its consumers.
            if (component is Component.Compilation) {
                compilerOutputs[moduleName] = component.compilation.outputDirs.map { it.path }.sorted()
            }

            val targetPlatform = targetPlatform(
                platformIds = component.platforms,
                konanTargets = component.konanTargets(),
                wasmTargets = component.wasmTargets(),
                jvmTarget = inferJvmTarget(moduleName, component, components, componentResults, compilerArguments),
                projectPlatforms = projectPlatforms,
            )
            val arguments = compilerArguments.getValue(component)?.let {
                facetCompilerArguments(it, targetPlatform, model.kotlinGradlePluginVersion)
            }
            kotlinSettings += KotlinSettingsData(
                name = "Kotlin",
                sourceRoots = contentRoots.flatMap { it.sourceRoots }.map { it.path },
                configFileItems = emptyList(),
                module = moduleName,
                useProjectSettings = false,
                implementedModuleNames = emptyList(),
                dependsOnModuleNames = resolved.dependsOn.toList(),
                additionalVisibleModuleNames = resolved.additionalVisible,
                productionOutputPath = null,
                testOutputPath = null,
                sourceSetNames = component.otherSourceSetNames().map { "$fqn.$it" },
                isTestModule = component.isTest,
                // The module name, as the JVM import writes it. The Kotlin plugin writes the Gradle module id. Only the
                // non-HMPP path of `org.jetbrains.kotlin.idea.base.facet.implementedModules` compares it, and KGP
                // 1.9.20+ always uses HMPP.
                externalProjectId = moduleName,
                isHmppEnabled = true,
                // Only the "package does not match the directory" check reads this, and only for JVM. It is left out.
                pureKotlinSourceFolders = emptyList(),
                kind = component.kind,
                compilerArguments = arguments?.compilerArguments,
                additionalArguments = arguments?.additionalArguments,
                scriptTemplates = null,
                scriptTemplatesClasspath = null,
                copyJsLibraryFiles = false,
                outputDirectoryForJsLibraryFiles = null,
                targetPlatform = targetPlatform.serializeComponentPlatforms(),
                externalSystemRunTasks = emptyList(),
                version = 5,
                flushNeeded = false,
            )
        }
        return KotlinMultiplatformModules(
            modules = modules,
            kotlinSettings = kotlinSettings,
            compilerOutputs = compilerOutputs,
            sourceSetNames = components.mapTo(HashSet()) { it.name },
        )
    }

    /** The dependencies of one module, and the module names that its Kotlin settings refer to. */
    private class ResolvedDependencies(
        val dependencies: List<DependencyData>,
        val dependsOn: Set<String>,
        val additionalVisible: Set<String>,
    )

    /**
     * Converts the KGP dependencies of [component], in KGP order. A source dependency gives a module dependency, and
     * its dependsOn and friend kinds also go to the Kotlin settings. A binary dependency gives a project library.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateModuleDependenciesWithDependenciesContainer`
     */
    private fun resolveDependencies(
        fqn: String,
        component: Component,
        kgpDependencies: List<IdeaKotlinDependency>,
        dependencyIndex: DependencyFileIndex,
    ): ResolvedDependencies {
        val dependencies = LinkedHashSet<DependencyData>()
        // dependsOn is the transitive chain inside this project, most specific first. The model-side closure comes
        // first (`org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.doCreateSourceSetInfo`), then the KGP
        // dependsOn edges; the set merges the two.
        val dependsOn = LinkedHashSet(component.declaredDependsOnClosure().map { "$fqn.$it" })
        val additionalVisible = LinkedHashSet(component.additionalVisible().map { "$fqn.$it" })

        for (dependency in kgpDependencies) {
            when (dependency) {
                is IdeaKotlinSourceDependency -> {
                    val targetFqn = moduleFqn(dependency.coordinates.project) ?: run {
                        LOG.info("$fqn.${component.name}: no module for the source dependency ${dependency.coordinates}")
                        null
                    } ?: continue
                    val targetModule = "$targetFqn.${dependency.coordinates.sourceSetName}"
                    dependencies += DependencyData.Module(targetModule, DependencyDataScope.COMPILE)
                    when (dependency.type) {
                        IdeaKotlinSourceDependency.Type.DependsOn -> dependsOn += targetModule
                        IdeaKotlinSourceDependency.Type.Friend -> additionalVisible += targetModule
                        IdeaKotlinSourceDependency.Type.Regular -> Unit
                    }
                }
                // Substituted with the modules that build the artifact
                // (`org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.KotlinProjectArtifactDependencyResolver`).
                is IdeaKotlinProjectArtifactDependency -> {
                    dependency.artifactsClasspath.forEach { file ->
                        dependencyIndex.get(file).filterIsInstance<DependencyData.Module>().forEach { module ->
                            dependencies += DependencyData.Module(module.name, DependencyDataScope.COMPILE)
                        }
                    }
                    if (component.platforms == setOf(KotlinPlatformId.ANDROID_JVM)) {
                        androidProjectArtifactModules(fqn, component, dependency.coordinates).forEach { module ->
                            dependencies += DependencyData.Module(module, DependencyDataScope.COMPILE)
                        }
                    }
                }
                is IdeaKotlinBinaryDependency -> dependencies += binaryDependency(fqn, dependency) ?: continue
            }
        }
        return ResolvedDependencies(dependencies.toList(), dependsOn, additionalVisible)
    }

    /** The Android SDK library of an Android-KMP project, or null when the model has no boot classpath. */
    private fun androidSdkDependency(fqn: String, model: KotlinMultiplatformModel): DependencyData.Library? {
        if (model.androidBootClasspath.isEmpty()) return null
        val library = LibraryData(
            name = ANDROID_SDK_LIBRARY,
            module = fqn,
            type = null,
            roots = model.androidBootClasspath.map { LibraryRootData(it.absolutePath, "CLASSES") },
        )
        librariesByName.merge(ANDROID_SDK_LIBRARY, library) { old, new -> old.copy(roots = (old.roots + new.roots).distinct()) }
        return DependencyData.Library(ANDROID_SDK_LIBRARY, DependencyDataScope.COMPILE)
    }

    /**
     * A library dependency for a KGP binary dependency. An unresolved dependency gets no library, so the workspace
     * post-processing reports it as unresolved.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.addDependency`
     */
    private fun binaryDependency(fqn: String, dependency: IdeaKotlinBinaryDependency): DependencyData.Library? {
        val coordinates = dependency.coordinates ?: return null
        val name = if (dependency.isNativeDistribution) nativeDistributionLibraryName(dependency) else "Gradle: ${coordinates.displayString}"
        if (dependency is IdeaKotlinUnresolvedBinaryDependency) {
            LOG.info("$fqn: unresolved dependency ${coordinates.displayString}: ${dependency.cause}")
            return DependencyData.Library(name, DependencyDataScope.COMPILE)
        }
        val resolved = dependency as IdeaKotlinResolvedBinaryDependency
        val roots = buildList {
            if (resolved.isKotlinCompileBinaryType) resolved.classpath.forEach { add(LibraryRootData(it.absolutePath, "CLASSES")) }
            resolved.sourcesClasspath.forEach { add(LibraryRootData(it.absolutePath, "SOURCES")) }
            resolved.documentationClasspath.forEach { add(LibraryRootData(it.absolutePath, "JAVADOC")) }
        }
        // Keyed by name, which the Java import also writes (`Gradle: g:a:v`). For the same name, the workspace keeps the
        // entry that comes first, and the Java import's libraries come first.
        librariesByName.merge(
            name,
            // `type` is null as in the IDE: the analyzer detects the Kotlin library kind from the roots.
            LibraryData(name = name, module = fqn, type = null, roots = roots),
        ) { old, new -> old.copy(roots = (old.roots + new.roots).distinct()) }
        return DependencyData.Library(name, DependencyDataScope.COMPILE)
    }

    /**
     * The name of a Kotlin/Native distribution library: `Kotlin/Native: stdlib`,
     * `Kotlin/Native: posix | [(ios_arm64, ios_x64)]`.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.buildNativeDistributionInternalLibraryName`
     */
    private fun nativeDistributionLibraryName(dependency: IdeaKotlinBinaryDependency): String = buildString {
        append("Kotlin/Native: ")
        append(dependency.coordinates?.module)
        if (!dependency.isNativeStdlib) {
            val klibExtra = dependency.klibExtra
            when {
                klibExtra?.commonizerTarget != null -> append(" | [${klibExtra.commonizerTarget}]")
                klibExtra?.nativeTargets != null -> append(" | ${klibExtra.nativeTargets.orEmpty().joinToString(", ")}")
            }
        }
    }

    /** The project module of [coordinates]. */
    private fun moduleFqn(coordinates: IdeaKotlinProjectCoordinates): String? = moduleFqnByIdentityPath[identityPath(coordinates)]

    /**
     * The identity path of [coordinates]. It follows the rules of the IDE, except that the root project of the build is
     * `":"` instead of its name.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.KotlinProjectModuleId`
     */
    private fun identityPath(coordinates: IdeaKotlinProjectCoordinates): String = when {
        coordinates.buildPath == ":" -> coordinates.projectPath
        coordinates.projectPath == ":" -> coordinates.buildPath
        else -> coordinates.buildPath + coordinates.projectPath
    }

    /**
     * The modules of a project dependency that AGP resolved for the Android source set [component]:
     * - An AGP project gives the module of its active variant.
     * - An Android-KMP library gives its main Android source set and the source sets that it depends on.
     *
     * The AGP and KMP models of the build tell the two apart. A dependency on test fixtures gives the main variant.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.android.extensions.KotlinAndroidProjectArtifactDependencyResolver`
     */
    private fun androidProjectArtifactModules(fqn: String, component: Component, coordinates: IdeaKotlinProjectCoordinates): List<String> {
        val identityPath = identityPath(coordinates)
        androidModuleByBuildTreePath[identityPath]?.let { return listOf(it) }
        val targetFqn = moduleFqnByIdentityPath[identityPath] ?: return emptyList()
        val targetModel = models.getValue(targetFqn)
        val mainCompilation = targetModel.compilations.firstOrNull {
            it.platform == KotlinPlatformId.ANDROID_JVM && !it.isTest && !it.isManagedByAndroidPlugin
        } ?: run {
            LOG.warn("$fqn.${component.name}: no main Android source set in the Kotlin Multiplatform project $identityPath")
            return emptyList()
        }
        val dependsOn = (mainCompilation.allSourceSets - mainCompilation.declaredSourceSets).sortedWith(dependsOnOrder(targetModel))
        return (dependsOn + mainCompilation.fullName).map { "$targetFqn.$it" }
    }

    /**
     * The lowest JVM target of [component] and of the modules that depend on it. The default target applies when none
     * sets one.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.inferJvmTarget`
     */
    private fun inferJvmTarget(
        moduleName: String,
        component: Component,
        components: List<Component>,
        resolved: Map<Component, ResolvedDependencies>,
        arguments: Map<Component, CommonCompilerArguments?>,
    ): JvmTarget {
        val dependents = components.filter { other -> moduleName in resolved.getValue(other).dependsOn }
        return (dependents + component).mapNotNull { jvmTargetOf(arguments[it]) }.minOrNull() ?: JvmTarget.DEFAULT
    }

    /** The compilations whose source sets include [sourceSetName] directly or through dependsOn. */
    private fun compilationsOf(sourceSetName: String, model: KotlinMultiplatformModel): List<KotlinMultiplatformCompilation> =
        model.compilations.filter { !it.isManagedByAndroidPlugin && sourceSetName in it.allSourceSets }

    /**
     * A source set comes before the source sets that it depends on. Like the IDE code, it is not a total order
     * (unrelated source sets compare equal), which is enough for the few source sets of a compilation.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.sourceSetOrderComparator`
     */
    private fun dependsOnOrder(model: KotlinMultiplatformModel): Comparator<String> {
        val allDependsOn = model.sourceSets.associate { it.name to it.allDependsOn }
        return Comparator { a, b ->
            when {
                b in allDependsOn[a].orEmpty() -> -1
                a in allDependsOn[b].orEmpty() -> 1
                else -> 0
            }
        }
    }

    /** A compilation module or a source set module. */
    private sealed class Component {
        abstract val name: String
        abstract val platforms: Set<String>
        abstract val isTest: Boolean
        abstract val kind: KotlinSettingsData.KotlinModuleKind

        /** The source set that gives the module its content roots. A compilation uses its default source set. */
        abstract val sourceSet: KotlinMultiplatformSourceSet?

        abstract fun compilerArguments(): CommonCompilerArguments?
        abstract fun declaredDependsOnClosure(): List<String>
        abstract fun additionalVisible(): Set<String>
        abstract fun otherSourceSetNames(): List<String>
        abstract fun konanTargets(): Set<String>
        abstract fun wasmTargets(): Set<String>

        /**
         * Non-JVM modules and the root common source sets use the Kotlin root types (`kotlin-source`, ...).
         *
         * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.postProcess`,
         * `org.jetbrains.kotlin.idea.gradleJava.migrateNonJvmSourceFolders`
         */
        fun usesKotlinRootTypes(): Boolean =
            platforms.any { it != KotlinPlatformId.JVM && it != KotlinPlatformId.ANDROID_JVM } || name == COMMON_MAIN || name == COMMON_TEST

        class Compilation(
            val compilation: KotlinMultiplatformCompilation,
            override val sourceSet: KotlinMultiplatformSourceSet?,
        ) : Component() {
            override val name: String get() = compilation.fullName
            override val platforms: Set<String> get() = setOf(compilation.platform)
            override val isTest: Boolean get() = compilation.isTest
            override val kind: KotlinSettingsData.KotlinModuleKind get() = KotlinSettingsData.KotlinModuleKind.COMPILATION_AND_SOURCE_SET_HOLDER

            override fun compilerArguments(): CommonCompilerArguments? {
                val arguments = compilation.compilerArguments ?: return null
                val platformKind = idePlatformKind(compilation.platform) ?: return null
                return parseCompilerArguments(arguments, platformKind)
            }

            // `doCreateSourceSetInfo(KotlinCompilation)`: the closure over the declared source sets, without them.
            override fun declaredDependsOnClosure(): List<String> = (compilation.allSourceSets - compilation.declaredSourceSets).toList()

            // The Kotlin plugin maps an empty set here: a compilation gets friends only from KGP.
            override fun additionalVisible(): Set<String> = emptySet()

            override fun otherSourceSetNames(): List<String> = compilation.allSourceSets.filter { it != name }

            override fun konanTargets(): Set<String> = setOfNotNull(compilation.konanTarget)

            override fun wasmTargets(): Set<String> = setOfNotNull(compilation.wasmTarget)
        }

        class SourceSet(
            override val sourceSet: KotlinMultiplatformSourceSet,
            private val compilations: List<KotlinMultiplatformCompilation>,
        ) : Component() {
            override val name: String get() = sourceSet.name
            override val platforms: Set<String> get() = sourceSet.platforms
            override val isTest: Boolean get() = sourceSet.isTest
            override val kind: KotlinSettingsData.KotlinModuleKind get() = KotlinSettingsData.KotlinModuleKind.SOURCE_SET_HOLDER

            override fun compilerArguments(): CommonCompilerArguments =
                synthesizeSourceSetCompilerArguments(sourceSet.platforms, sourceSet.languageSettings)

            override fun declaredDependsOnClosure(): List<String> = sourceSet.allDependsOn.toList()

            override fun additionalVisible(): Set<String> = sourceSet.additionalVisible

            override fun otherSourceSetNames(): List<String> = emptyList()

            // Only a source set of a single platform gets the targets of its compilations (pass 2 of
            // `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`).
            override fun konanTargets(): Set<String> =
                if (sourceSet.platforms.singleOrNull() == KotlinPlatformId.NATIVE) compilations.mapNotNullTo(LinkedHashSet()) { it.konanTarget }
                else emptySet()

            override fun wasmTargets(): Set<String> =
                if (sourceSet.platforms.singleOrNull() == KotlinPlatformId.WASM) compilations.mapNotNullTo(LinkedHashSet()) { it.wasmTarget }
                else emptySet()
        }
    }
}

private fun DependencyData.inTestScope(): DependencyData = when (this) {
    is DependencyData.Module -> if (scope == DependencyDataScope.COMPILE) copy(scope = DependencyDataScope.TEST) else this
    is DependencyData.Library -> if (scope == DependencyDataScope.COMPILE) copy(scope = DependencyDataScope.TEST) else this
    else -> this
}
