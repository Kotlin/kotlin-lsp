// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.kmp

import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformLanguageSettings
import com.jetbrains.ls.imports.utils.serializeNonDefaultCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.ManualLanguageFeatureSetting
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.serializeComponentPlatforms
import org.jetbrains.kotlin.idea.compiler.configuration.IdeKotlinVersion
import org.jetbrains.kotlin.idea.facet.applyCompilerArgumentsToFacetSettings
import org.jetbrains.kotlin.idea.facet.coerceAtMostVersion
import org.jetbrains.kotlin.idea.serialization.updateCompilerArguments
import org.jetbrains.kotlin.platform.IdePlatformKind
import org.jetbrains.kotlin.platform.JsPlatform
import org.jetbrains.kotlin.platform.SimplePlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.impl.CommonIdePlatformKind
import org.jetbrains.kotlin.platform.impl.JsIdePlatformKind
import org.jetbrains.kotlin.platform.impl.JvmIdePlatformKind
import org.jetbrains.kotlin.platform.impl.NativeIdePlatformKind
import org.jetbrains.kotlin.platform.impl.WasmIdePlatformKind
import org.jetbrains.kotlin.platform.impl.WasmJsIdePlatformKind
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.platform.wasm.WasmPlatform
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import java.io.File

/**
 * The `org.jetbrains.kotlin.idea.projectModel.KotlinPlatform` ids of the IntelliJ Kotlin plugin, as the Gradle-side
 * model writes them.
 */
internal object KotlinPlatformId {
    const val COMMON: String = "common"
    const val JVM: String = "jvm"
    const val ANDROID_JVM: String = "androidJvm"
    const val JS: String = "js"
    const val WASM: String = "wasm"
    const val NATIVE: String = "native"
}

/**
 * The platform kind of a platform id. Android compiles with the JVM kind. Both Wasm toolings claim the `wasm` platform.
 * Their kinds share the arguments class, and the target platform comes from the Wasm targets.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.base.codeInsight.tooling.IdePlatformKindTooling.kind`
 */
internal fun idePlatformKind(platformId: String): IdePlatformKind? = when (platformId) {
    KotlinPlatformId.COMMON -> CommonIdePlatformKind
    KotlinPlatformId.JVM, KotlinPlatformId.ANDROID_JVM -> JvmIdePlatformKind
    KotlinPlatformId.JS -> JsIdePlatformKind
    KotlinPlatformId.WASM -> WasmJsIdePlatformKind
    KotlinPlatformId.NATIVE -> NativeIdePlatformKind
    else -> null
}

/**
 * The target platform of a module in the `org.jetbrains.kotlin.config.serializeComponentPlatforms` form that
 * `org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity.targetPlatform` stores (`"JVM [11]"`,
 * `"Native []/Native [ios_arm64]"`):
 * - JVM uses the lowest JVM target of the module and of the modules that depend on it ([jvmTarget]).
 * - Native and Wasm use the concrete targets of the compilations.
 * - JS and common use the default platform of their kind. The kind keeps only the platforms that the project has
 *   ([projectPlatforms]). So `commonMain` of a project with JVM and Wasm targets also lists `JVM [1.8]` and
 *   `wasm-wasi [WASI]`, as in the IDE.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.configureFacet`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.toSimplePlatforms`
 */
internal fun serializedTargetPlatform(
    platformIds: Set<String>,
    konanTargets: Set<String>,
    wasmTargets: Set<String>,
    jvmTarget: JvmTarget,
    projectPlatforms: Set<String>,
): String {
    val simplePlatforms = platformIds.mapNotNull(::idePlatformKind).flatMap { kind ->
        when (kind) {
            is JvmIdePlatformKind -> JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget).componentPlatforms
            is NativeIdePlatformKind -> NativePlatforms.nativePlatformByTargetNames(konanTargets).componentPlatforms
            is WasmIdePlatformKind -> WasmPlatforms.wasmPlatformByTargetNames(wasmTargets).componentPlatforms
            else -> kind.defaultPlatform.filter { it.isRelevantFor(projectPlatforms) }
        }
    }.toSet()
    return TargetPlatform(simplePlatforms).serializeComponentPlatforms()
}

/**
 * Whether a default platform of a kind applies to the project. Common modules of an Android project still see JVM.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.isRelevantFor`
 */
private fun SimplePlatform.isRelevantFor(projectPlatforms: Set<String>): Boolean = when (this) {
    is JvmPlatform -> projectPlatforms.any { it == KotlinPlatformId.JVM || it == KotlinPlatformId.ANDROID_JVM || it == KotlinPlatformId.COMMON }
    is JsPlatform -> KotlinPlatformId.JS in projectPlatforms
    is WasmPlatform -> KotlinPlatformId.WASM in projectPlatforms
    is NativePlatform -> KotlinPlatformId.NATIVE in projectPlatforms
    else -> true
}

/**
 * The arguments class of the platform kind, filled from [arguments].
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.createCompilerArguments`
 */
internal fun parseCompilerArguments(arguments: List<String>, platformKind: IdePlatformKind): CommonCompilerArguments {
    val compilerArguments = platformKind.argumentsClass.getDeclaredConstructor().newInstance()
    parseCommandLineArguments(arguments, compilerArguments)
    return compilerArguments
}

/**
 * The compiler arguments of a source set module, which has no compile task of its own.
 *
 * It uses the arguments class of the single platform kind, or the metadata (common) class for a shared source set. It
 * fills the class from the language settings of the source set.
 *
 * LSP port of IDE code: the `info.compilerArguments` block of
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.mpp.doCreateSourceSetInfo` for a source set
 */
internal fun synthesizeSourceSetCompilerArguments(
    platformIds: Set<String>,
    languageSettings: KotlinMultiplatformLanguageSettings,
): CommonCompilerArguments {
    val platformKind = platformIds.mapNotNull(::idePlatformKind).toSet().singleOrNull() ?: CommonIdePlatformKind
    return parseCompilerArguments(emptyList(), platformKind).also {
        it.multiPlatform = true
        it.languageVersion = languageSettings.languageVersion
        it.apiVersion = languageSettings.apiVersion
        it.progressiveMode = languageSettings.progressiveMode
        it.internalArguments = languageSettings.enabledLanguageFeatures.mapNotNull { name ->
            val feature = LanguageFeature.fromString(name) ?: return@mapNotNull null
            ManualLanguageFeatureSetting(feature, LanguageFeature.State.ENABLED, "-XXLanguage:+$name")
        }
        it.optIn = languageSettings.optInAnnotationsInUse.toTypedArray()
        it.pluginOptions = languageSettings.compilerPluginArguments.toTypedArray()
        it.pluginClasspaths = languageSettings.compilerPluginClasspath.map(File::getPath).toTypedArray()
        it.freeArgs = languageSettings.freeCompilerArgs.toMutableList()
    }
}

/** The JVM target of [arguments], if they are JVM arguments that set it. */
internal fun jvmTargetOf(arguments: CommonCompilerArguments?): JvmTarget? =
    (arguments as? K2JVMCompilerArguments)?.jvmTarget?.let(JvmTarget::fromString)

/** The serialized compiler arguments and the additional arguments string of a Kotlin facet. */
internal data class FacetCompilerArguments(val compilerArguments: String, val additionalArguments: String?)

/**
 * Applies [arguments] to a Kotlin facet with `org.jetbrains.kotlin.idea.facet.applyCompilerArgumentsToFacetSettings`,
 * and serializes the result for `org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity`.
 *
 * First, the facet gets the language version of [kotlinGradlePluginVersion], and the API version of the same value,
 * coerced to that version. [arguments] that set a version override them. The facet keeps only the arguments that it
 * exposes. The rest goes to the additional arguments string. Then the automatic version advance is switched off.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.facet.initializeIfNeeded`,
 * `org.jetbrains.kotlin.idea.facet.noVersionAutoAdvance` as
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService` calls it
 */
internal fun facetCompilerArguments(arguments: CommonCompilerArguments, kotlinGradlePluginVersion: String?): FacetCompilerArguments {
    val settings = KotlinFacetSettings().apply {
        // `org.jetbrains.kotlin.idea.facet.initializeIfNeeded` creates this bean in the IDE. The arguments are copied
        // into it only if the classes match.
        compilerArguments = arguments.javaClass.getDeclaredConstructor().newInstance()
        compilerSettings = CompilerSettings()
        // The IDE falls back to its bundled compiler version. Here the facet then has no version, and the analyzer uses its default.
        val compilerVersion = kotlinGradlePluginVersion?.let(IdeKotlinVersion::opt)
        if (compilerVersion != null) {
            languageLevel = compilerVersion.languageVersion
            apiLevel = languageLevel?.coerceAtMostVersion(compilerVersion)
        }
    }
    applyCompilerArgumentsToFacetSettings(arguments, settings, module = null, modelsProvider = null)
    settings.updateCompilerArguments {
        autoAdvanceLanguageVersion = false
        autoAdvanceApiVersion = false
    }
    return FacetCompilerArguments(
        compilerArguments = serializeNonDefaultCompilerArguments(settings.compilerArguments!!),
        additionalArguments = settings.compilerSettings?.additionalArguments?.takeIf { it.isNotBlank() },
    )
}
