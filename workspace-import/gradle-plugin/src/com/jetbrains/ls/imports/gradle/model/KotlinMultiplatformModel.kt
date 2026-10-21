// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model

import java.io.File
import java.io.Serializable

/**
 * The Kotlin Multiplatform structure of one Gradle project.
 *
 * The model holds plain data only. The KGP dependency model is kept as serialized bytes in [dependencies], because a
 * KGP class in the model fails to deserialize across two plugin classloaders (see
 * `com.jetbrains.ls.imports.gradle.ToolingModelTypesTest`).
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleTooling.KotlinMPPGradleModelBuilder`
 */
interface KotlinMultiplatformModel : Serializable {
    /**
     * The Gradle identity path of the project (`":"`, `":app"`, `":included:lib"`). It matches the build path and
     * project path that KGP writes into `org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectCoordinates`, so a
     * source dependency can find this project.
     */
    val projectIdentityPath: String

    /**
     * The KGP version (`2.4.20`). The IDE takes the default language and API version of a facet from it
     * (`org.jetbrains.kotlin.idea.facet.initializeIfNeeded`). Null when KGP does not report it.
     */
    val kotlinGradlePluginVersion: String?

    val sourceSets: List<KotlinMultiplatformSourceSet>

    /**
     * The compilations of all targets, without the `metadata` target. That target only packs the shared source sets
     * into metadata klibs, and the shared source sets are modules of their own.
     */
    val compilations: List<KotlinMultiplatformCompilation>

    /**
     * Source set name to the `org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency` protobuf messages that KGP's
     * `org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport` resolved for it, in KGP order. The order is the
     * classpath order.
     */
    val dependencies: Map<String, List<ByteArray>>

    /**
     * The Android SDK boot classpath (`android.jar`) of an Android-KMP library (`com.android.kotlin.multiplatform.library`).
     * KGP does not resolve it. In the IDE the Android plugin adds it as the SDK of the Android source sets.
     */
    val androidBootClasspath: List<File>
}

/** A Kotlin source set (`commonMain`, `iosMain`, `jvmTest`, ...). */
interface KotlinMultiplatformSourceSet : Serializable {
    val name: String
    val sourceDirs: Set<File>
    val resourceDirs: Set<File>
    val generatedSourceDirs: Set<File>
    val declaredDependsOn: Set<String>

    /** The transitive closure of [declaredDependsOn]. */
    val allDependsOn: Set<String>
    val additionalVisible: Set<String>

    /**
     * `org.jetbrains.kotlin.idea.projectModel.KotlinPlatform` ids (`common`, `jvm`, `androidJvm`, `js`, `wasm`,
     * `native`) that this source set compiles for.
     */
    val platforms: Set<String>

    /** True when all compilations that include this source set are test compilations. */
    val isTest: Boolean

    /**
     * True for the legacy `androidTarget()` source sets, which the Android importer owns.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.projectModel.KotlinSourceSet.isManagedByComAndroidLibraryPlugin`
     */
    val isManagedByAndroidPlugin: Boolean
    val languageSettings: KotlinMultiplatformLanguageSettings
}

/** The `languageSettings` block of a Kotlin source set. */
interface KotlinMultiplatformLanguageSettings : Serializable {
    val languageVersion: String?
    val apiVersion: String?
    val progressiveMode: Boolean
    val enabledLanguageFeatures: Set<String>
    val optInAnnotationsInUse: Set<String>
    val compilerPluginArguments: List<String>
    val compilerPluginClasspath: Set<File>
    val freeCompilerArgs: List<String>
}

/** A compilation of a Kotlin target (`jvm/main`, `iosArm64/test`, ...). */
interface KotlinMultiplatformCompilation : Serializable {
    /** The target classifier plus the capitalized compilation name (`jvmMain`). It equals its default source set name. */
    val fullName: String

    /** The `org.jetbrains.kotlin.idea.projectModel.KotlinPlatform` id of the target. */
    val platform: String

    /** The Kotlin/Native target name (`ios_arm64`) of a native compilation. */
    val konanTarget: String?

    /** The Wasm target alias (`wasm-js`, `wasm-wasi`) of a Wasm compilation. */
    val wasmTarget: String?
    val declaredSourceSets: Set<String>

    /** [declaredSourceSets] plus their dependsOn closure. */
    val allSourceSets: Set<String>

    /** The arguments KGP passes to the compiler, or null when KGP cannot resolve them. */
    val compilerArguments: List<String>?

    /** The class, effective class and resource output directories. */
    val outputDirs: Set<File>

    /** The archive that the compilation's archive task produces, if the compilation has one. */
    val archiveFile: File?

    /** True when the compilation is associated with another one, as `test` is with `main`. */
    val isTest: Boolean

    /**
     * True for a compilation of the legacy `androidTarget()`.
     *
     * LSP port of IDE code: `org.jetbrains.kotlin.idea.projectModel.KotlinCompilation.isManagedByComAndroidLibraryPlugin`
     */
    val isManagedByAndroidPlugin: Boolean
}
