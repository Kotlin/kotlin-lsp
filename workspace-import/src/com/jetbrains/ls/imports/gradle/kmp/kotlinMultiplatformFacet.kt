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
import org.jetbrains.kotlin.config.createArguments
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
import java.util.Locale

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
 * The target platform of a module:
 * - JVM uses the lowest JVM target of the module and of the modules that depend on it ([jvmTarget]).
 * - Native and Wasm use the concrete targets of the compilations.
 * - JS and common use the default platform of their kind. The kind keeps only the platforms that the project has
 *   ([projectPlatforms]).
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.configureFacet`,
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.toSimplePlatforms`
 */
internal fun targetPlatform(
    platformIds: Set<String>,
    konanTargets: Set<String>,
    wasmTargets: Set<String>,
    jvmTarget: JvmTarget,
    projectPlatforms: Set<String>,
): TargetPlatform {
    val simplePlatforms = platformIds.mapNotNull(::idePlatformKind).flatMap { kind ->
        when (kind) {
            is JvmIdePlatformKind -> JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget).componentPlatforms
            is NativeIdePlatformKind -> NativePlatforms.nativePlatformByTargetNames(konanTargets).componentPlatforms
            is WasmIdePlatformKind -> WasmPlatforms.wasmPlatformByTargetNames(wasmTargets).componentPlatforms
            else -> kind.defaultPlatform.filter { it.isRelevantFor(projectPlatforms) }
        }
    }.toSet()
    return TargetPlatform(simplePlatforms)
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
 * First, the facet gets the arguments class of [targetPlatform], and the language version of [kotlinGradlePluginVersion].
 * The API version gets the same value, coerced to that version. [arguments] of the same class override them. Arguments of
 * a different class give only their plugin options. The facet keeps only the arguments that it exposes. The rest goes to
 * the additional arguments string. Then the automatic version advance is switched off.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.facet.initializeIfNeeded`,
 * `org.jetbrains.kotlin.idea.facet.noVersionAutoAdvance` as
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService` calls it
 */
internal fun facetCompilerArguments(
    arguments: CommonCompilerArguments,
    targetPlatform: TargetPlatform,
    kotlinGradlePluginVersion: String?,
): FacetCompilerArguments {
    val settings = KotlinFacetSettings().apply {
        // The language server has no project-level arguments to copy into the bean.
        compilerArguments = targetPlatform.createArguments()
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
    settings.removeScriptingAndKaptPlugins()
    return FacetCompilerArguments(
        compilerArguments = serializeNonDefaultCompilerArguments(settings.compilerArguments!!),
        additionalArguments = settings.compilerSettings?.additionalArguments?.takeIf { it.isNotBlank() },
    )
}

/** `org.jetbrains.kotlin.scripting.KOTLIN_SCRIPTING_PLUGIN_ID`, which is not on the classpath of this module. */
private const val SCRIPTING_PLUGIN_ID = "kotlin.scripting"

/** The kapt plugin id. The IDE does not have kapt either, so it also uses the literal. */
private const val KAPT_PLUGIN_ID = "org.jetbrains.kotlin.kapt3"

private val SCRIPTING_PLUGIN_JAR_REGEXES: List<Regex> = listOf(
    "scripting-gradle", // obsolete artifact name, only for compatibility with 1.2.5x, where it was introduced (and immediately dropped afterwards)
    "scripting-compiler",
    "scripting-compiler-embeddable",
).map { "(kotlin-)?(maven-)?$it-.*\\.jar".toRegex() }

private val KAPT_PLUGIN_JAR_REGEX: Regex = "kotlin-annotation-processing(-gradle|-embeddable)?-[0-9].*\\.jar".toRegex()

/**
 * Removes the jars and the options of the Kotlin scripting plugin and of kapt from the compiler arguments of the facet.
 *
 * The other import handlers of the IDE only replace a plugin jar with the bundled one, and only when the Gradle
 * delegated build is off. They are not ported.
 *
 * LSP port of IDE code: `org.jetbrains.kotlin.idea.compilerPlugin.scripting.gradleJava.ScriptingGradleProjectImportHandler`,
 * `org.jetbrains.kotlin.idea.compilerPlugin.kapt.gradleJava.KaptGradleProjectImportHandler` as
 * `org.jetbrains.kotlin.idea.gradleJava.configuration.KotlinSourceSetDataService.postProcess` calls them on each facet
 */
private fun KotlinFacetSettings.removeScriptingAndKaptPlugins() {
    updateCompilerArguments {
        pluginOptions = pluginOptions
            .filter { !it.startsWith("plugin:$SCRIPTING_PLUGIN_ID:") && !it.startsWith("plugin:$KAPT_PLUGIN_ID:") }
            .toTypedArray()
        pluginClasspaths = pluginClasspaths
            .filter { path ->
                val fileName = jarFileName(path) ?: return@filter true
                SCRIPTING_PLUGIN_JAR_REGEXES.none { fileName.matches(it) } && !fileName.lowercase(Locale.US).matches(KAPT_PLUGIN_JAR_REGEX)
            }
            .toTypedArray()
    }
}

/** The file name of a jar path, or `null` for a path without a directory, which the IDE handlers keep. */
private fun jarFileName(path: String): String? {
    val lastIndexOfFile = path.lastIndexOfAny(charArrayOf('/', File.separatorChar)).takeIf { it >= 0 } ?: return null
    return path.drop(lastIndexOfFile + 1)
}
