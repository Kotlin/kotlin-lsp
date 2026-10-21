// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.utils

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import java.io.File

/*
 * Reflective access to the Kotlin Multiplatform DSL of the Kotlin Gradle plugin (KGP). KGP is not on the compile
 * classpath of this plugin, and its version is the one of the imported project. This reads the subset of
 * `org.jetbrains.kotlin.idea.gradleTooling.reflect` (IntelliJ Kotlin plugin) that the
 * [com.jetbrains.ls.imports.gradle.model.builder.KotlinMultiplatformModelBuilder] needs, with the same method names and
 * the same fallbacks. A method that an older or newer KGP may lack is read without an error log.
 */

/**
 * The `kotlin` extension of [project] if it is a multiplatform extension, else null.
 *
 * Every Kotlin project has a `kotlin` extension. A JVM or Android one is a
 * `org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension` with one `target`, and only
 * `org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension` has
 * `org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer.getTargets`. A plugin id check would need both KGP ids
 * (`org.jetbrains.kotlin.multiplatform` and the legacy `kotlin-multiplatform`).
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder.shouldBuild`
 */
internal fun kotlinMultiplatformExtension(project: Project): KotlinMultiplatformExtensionReflection? {
    val kotlin = project.extensions.findByName("kotlin") ?: return null
    val targets = kotlin.reflected.call("getTargets") ?: return null
    return KotlinMultiplatformExtensionReflection(project, kotlin.reflected, targets)
}

internal class KotlinMultiplatformExtensionReflection(
    val project: Project,
    val reflected: Reflected.Instance,
    private val reflectedTargets: Reflected.Instance,
) {
    val targets: List<KotlinMppTargetReflection> by lazy {
        reflectedTargets.unwrapAs<Iterable<*>>()?.filterNotNull()?.map { KotlinMppTargetReflection(project, it.reflected) }.orEmpty()
    }

    val sourceSets: List<KotlinMppSourceSetReflection> by lazy {
        reflected.call("getSourceSets")?.unwrapAs<Iterable<*>>()?.filterIsInstance<Named>()
            ?.map { KotlinMppSourceSetReflection(project, it.reflected) }
            ?: run {
                project.logger.error("Failed to find 'sourceSets' in Kotlin multiplatform extension")
                emptyList()
            }
    }

    /**
     * The KGP version, from `org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt.getKotlinPluginVersion`.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.reflect.KotlinExtensionReflection`
     */
    val kotlinPluginVersion: String? by lazy {
        val wrapper = kgpClass("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt") ?: return@lazy null
        staticReflected(wrapper).call("getKotlinPluginVersion", param(project))?.instance as? String
    }

    /** A KGP class, loaded with the classloader of the `kotlin` extension, or null when this KGP does not have it. */
    fun kgpClass(className: String): Class<*>? =
        runCatching { Class.forName(className, false, reflected.instance.javaClass.classLoader) }.getOrNull()
}

internal class KotlinMppTargetReflection(val project: Project, val reflected: Reflected.Instance) {
    val name: String get() = (reflected.instance as Named).name

    /**
     * `org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget` produces the metadata klibs only. The IDE never
     * imports it as a module.
     */
    val isMetadataTarget: Boolean by lazy { reflected.isInstanceOf("org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget") }

    /** The legacy `androidTarget()` with `com.android.library`. The Android importer owns its modules. */
    val isKotlinAndroidTarget: Boolean by lazy { reflected.isInstanceOf("org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget") }

    val disambiguationClassifier: String? by lazy { reflected.call("getDisambiguationClassifier")?.unwrapAs<String>() }

    /**
     * The `org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType` name: `common`, `jvm`, `androidJvm`, `js`, `wasm` or
     * `native`.
     */
    val platformType: String? by lazy {
        reflected.call("getPlatformType")?.call("getName")?.unwrapAs<String>() ?: run {
            project.logger.error("Failed to find 'platformType' in Kotlin target '$name'")
            return@lazy null
        }
    }

    /**
     * Only for the Wasm target fallback, when a Wasm compilation gives no target.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.populateMppModuleDataNode`
     */
    val presetName: String? by lazy { reflected.call("getPreset")?.call("getName")?.unwrapAs<String>() }

    val compilations: List<KotlinMppCompilationReflection> by lazy {
        reflected.call("getCompilations")?.unwrapAs<Iterable<*>>()?.filterNotNull()
            ?.map { KotlinMppCompilationReflection(project, it.reflected) }
            ?: run {
                project.logger.error("Failed to find 'compilations' in Kotlin target '$name'")
                emptyList()
            }
    }
}

internal class KotlinMppCompilationReflection(val project: Project, val reflected: Reflected.Instance) {
    val name: String get() = (reflected.instance as Named).name

    /** The source sets added to the compilation directly. */
    val sourceSets: List<Named> by lazy {
        reflected.call("getKotlinSourceSets")?.unwrapAs<Iterable<*>>()?.filterIsInstance<Named>() ?: run {
            project.logger.error("Failed to find 'kotlinSourceSets' in Kotlin compilation '$name'")
            emptyList()
        }
    }

    /** The compilations that this one is associated with. A non-empty list marks a test compilation. */
    val associateCompilations: List<Any> by lazy {
        reflected.call("getAssociateWith")?.unwrapAs<Iterable<*>>()?.filterNotNull() ?: run {
            project.logger.error("Failed to find 'associateWith' in Kotlin compilation '$name'")
            emptyList()
        }
    }

    val compileKotlinTaskName: String? by lazy {
        reflected.call("getCompileKotlinTaskName")?.unwrapAs<String>() ?: run {
            project.logger.error("Failed to find 'compileKotlinTaskName' in Kotlin compilation '$name'")
            return@lazy null
        }
    }

    val classesDirs: Iterable<File> by lazy {
        reflected.call("getOutput")?.call("getClassesDirs")?.unwrapAs<FileCollection>() ?: run {
            project.logger.error("Failed to find 'output.classesDirs' in Kotlin compilation '$name'")
            emptyList()
        }
    }

    val resourcesDir: File? by lazy { reflected.call("getOutput")?.call("getResourcesDir")?.unwrapAs<File>() }

    /**
     * The Kotlin/Native target name (`ios_arm64`). Only native compilations have
     * `org.jetbrains.kotlin.gradle.plugin.mpp.AbstractKotlinNativeCompilation.getKonanTarget`.
     */
    val konanTargetName: String? by lazy { reflected.call("getKonanTarget")?.call("getName")?.unwrapAs<String>() }

    /**
     * The Wasm target alias (`wasm-js`). Only Wasm compilations of KGP 2.0+ have
     * `org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation.getWasmTarget`.
     */
    val wasmTargetName: String? by lazy { reflected.call("getWasmTarget")?.call("getAlias")?.unwrapAs<String>() }

    val archiveTaskName: String? by lazy { reflected.call("getArchiveTaskName")?.unwrapAs<String>() }
}

internal class KotlinMppSourceSetReflection(val project: Project, val reflected: Reflected.Instance) {
    val name: String get() = (reflected.instance as Named).name

    val kotlin: SourceDirectorySet? by lazy {
        reflected.call("getKotlin")?.unwrapAs<SourceDirectorySet>() ?: run {
            project.logger.error("Failed to find 'kotlin' in Kotlin source set '$name'")
            return@lazy null
        }
    }

    val resources: SourceDirectorySet? by lazy {
        reflected.call("getResources")?.unwrapAs<SourceDirectorySet>() ?: run {
            project.logger.error("Failed to find 'resources' in Kotlin source set '$name'")
            return@lazy null
        }
    }

    /** The generated Kotlin dirs of newer KGP. The Kotlin plugin reads them only for KGP 2.3.0+; this reads them whenever the method exists. */
    val generatedKotlin: SourceDirectorySet? by lazy { reflected.call("getGeneratedKotlin")?.unwrapAs<SourceDirectorySet>() }

    val dependsOn: Set<String> by lazy {
        reflected.call("getDependsOn")?.unwrapAs<Iterable<*>>()?.filterIsInstance<Named>()?.mapTo(LinkedHashSet()) { it.name }
            ?: run {
                project.logger.error("Failed to find 'dependsOn' in Kotlin source set '$name'")
                emptySet()
            }
    }

    val additionalVisibleSourceSets: Set<String> by lazy {
        reflected.call("getAdditionalVisibleSourceSets")?.unwrapAs<Iterable<*>>()?.filterIsInstance<Named>()
            ?.mapTo(LinkedHashSet()) { it.name }.orEmpty()
    }

    val languageSettings: KotlinMppLanguageSettingsReflection? by lazy {
        reflected.call("getLanguageSettings")?.let { KotlinMppLanguageSettingsReflection(project, it) } ?: run {
            project.logger.error("Failed to find 'languageSettings' in Kotlin source set '$name'")
            return@lazy null
        }
    }
}

internal class KotlinMppLanguageSettingsReflection(val project: Project, val reflected: Reflected.Instance) {
    val languageVersion: String? by lazy { reflected.call("getLanguageVersion")?.unwrapAs<String>() }

    val apiVersion: String? by lazy { reflected.call("getApiVersion")?.unwrapAs<String>() }

    // `unwrapAs<Boolean>` would test against the primitive class, which never matches the boxed result.
    val progressiveMode: Boolean by lazy { reflected.call("getProgressiveMode")?.instance as? Boolean ?: false }

    /**
     * KGP plans to remove `org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder.getEnabledLanguageFeatures`
     * (KT-84918), so a missing method is not an error.
     */
    val enabledLanguageFeatures: Set<String> by lazy { reflected.stringsOf("getEnabledLanguageFeatures").toSet() }

    /**
     * KGP renamed `org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder.experimentalAnnotationsInUse` to
     * `org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder.optInAnnotationsInUse`.
     */
    val optInAnnotationsInUse: Set<String> by lazy {
        (reflected.call("getOptInAnnotationsInUse") ?: reflected.call("getExperimentalAnnotationsInUse"))
            ?.unwrapAs<Iterable<*>>()?.mapTo(LinkedHashSet()) { it.toString() }.orEmpty()
    }

    val compilerPluginArguments: List<String> by lazy { reflected.stringsOf("getCompilerPluginArguments") }

    val compilerPluginClasspath: Set<File> by lazy {
        reflected.call("getCompilerPluginClasspath")?.unwrapAs<Iterable<*>>()?.filterIsInstance<File>().orEmpty().toSet()
    }

    val freeCompilerArgs: List<String> by lazy { reflected.stringsOf("getFreeCompilerArgs") }
}

/**
 * KGP's `org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport`: the IDE dependency resolution that KGP
 * implements itself. Null when the KGP of the project does not have it (an old KGP).
 */
internal class IdeMultiplatformImportReflection private constructor(val project: Project, val reflected: Reflected.Instance) {

    /**
     * The serialized `org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency` messages of [sourceSetName], in KGP
     * classpath order. This runs KGP's own resolution, so a failure is logged and gives no dependencies for this source
     * set only.
     */
    fun resolveDependenciesSerialized(sourceSetName: String): List<ByteArray> =
        runCatching {
            reflected.call("resolveDependenciesSerialized", param(sourceSetName))?.unwrapAs<Iterable<*>>()
                ?.filterIsInstance<ByteArray>().orEmpty()
        }.getOrElse { e ->
            project.logger.error("Failed to resolve the dependencies of Kotlin source set '$sourceSetName'", e)
            emptyList()
        }

    companion object {
        fun create(kotlin: KotlinMultiplatformExtensionReflection): IdeMultiplatformImportReflection? =
            kgpIdeService(kotlin, "org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport")
                ?.let { IdeMultiplatformImportReflection(kotlin.project, it) }
    }
}

/**
 * KGP's `org.jetbrains.kotlin.gradle.plugin.ide.IdeCompilerArgumentsResolver`: the compiler arguments of a compile
 * task, as KGP would pass them.
 */
internal class IdeCompilerArgumentsResolverReflection private constructor(val project: Project, val reflected: Reflected.Instance) {

    /** This runs KGP's own argument resolution, so a failure is logged and gives no arguments for this task only. */
    fun resolveCompilerArguments(compileTask: Task): List<String>? =
        runCatching {
            reflected.call("resolveCompilerArguments", param(compileTask))?.unwrapAs<List<*>>()?.map { it.toString() }
        }.getOrElse { e ->
            project.logger.error("Failed to resolve the compiler arguments of ${compileTask.path}", e)
            null
        }

    companion object {
        fun create(kotlin: KotlinMultiplatformExtensionReflection): IdeCompilerArgumentsResolverReflection? =
            kgpIdeService(kotlin, "org.jetbrains.kotlin.gradle.plugin.ide.IdeCompilerArgumentsResolver")
                ?.let { IdeCompilerArgumentsResolverReflection(kotlin.project, it) }
    }
}

/**
 * The archive file of a [org.gradle.api.tasks.bundling.Jar]-like task. [org.gradle.api.provider.Provider] and
 * [org.gradle.api.file.RegularFile] come from the Gradle API, so no reflection is needed on them.
 */
internal fun Task.archiveFileOrNull(): File? =
    (reflected.call("getArchiveFile")?.unwrapAs<Provider<*>>()?.orNull as? RegularFile)?.asFile

/**
 * The effective output directory of a Kotlin compile task. The IDE code also has a fallback to
 * `org.jetbrains.kotlin.gradle.tasks.AbstractKotlinNativeCompile.getOutputFile` for old native compile tasks. This
 * port does not.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.reflect.KotlinNativeCompileReflection`
 */
internal fun Task.kotlinDestinationDirOrNull(): File? =
    reflected.call("getDestinationDirectory")?.unwrapAs<DirectoryProperty>()?.asFile?.orNull
    ?: reflected.call("getDestinationDir")?.unwrapAs<File>()

/** Loads a KGP IDE service class with the `kotlin` extension's classloader and calls its static `instance(Project)`. */
private fun kgpIdeService(kotlin: KotlinMultiplatformExtensionReflection, className: String): Reflected.Instance? {
    val clazz = kotlin.kgpClass(className) ?: return null
    return staticReflected(clazz).call("instance", param(kotlin.project)) ?: run {
        kotlin.project.logger.error("Failed to call 'instance' on $className")
        null
    }
}

private fun Reflected.Instance.isInstanceOf(className: String): Boolean =
    runCatching { Class.forName(className, false, instance.javaClass.classLoader).isInstance(instance) }.getOrDefault(false)

private fun Reflected.Instance.stringsOf(getter: String): List<String> =
    call(getter)?.unwrapAs<Iterable<*>>()?.map { it.toString() }.orEmpty()
