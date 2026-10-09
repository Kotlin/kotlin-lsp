// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.impl

import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformCompilation
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformLanguageSettings
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformModel
import com.jetbrains.ls.imports.gradle.model.KotlinMultiplatformSourceSet
import java.io.File

internal data class KotlinMultiplatformModelImpl(
    override val projectIdentityPath: String,
    override val kotlinGradlePluginVersion: String?,
    override val sourceSets: List<KotlinMultiplatformSourceSet>,
    override val compilations: List<KotlinMultiplatformCompilation>,
    override val dependencies: Map<String, List<ByteArray>>,
    override val androidBootClasspath: List<File>,
) : KotlinMultiplatformModel

internal data class KotlinMultiplatformSourceSetImpl(
    override val name: String,
    override val sourceDirs: Set<File>,
    override val resourceDirs: Set<File>,
    override val generatedSourceDirs: Set<File>,
    override val declaredDependsOn: Set<String>,
    override val allDependsOn: Set<String>,
    override val additionalVisible: Set<String>,
    override val platforms: Set<String>,
    override val isTest: Boolean,
    override val isManagedByAndroidPlugin: Boolean,
    override val languageSettings: KotlinMultiplatformLanguageSettings,
) : KotlinMultiplatformSourceSet

internal data class KotlinMultiplatformLanguageSettingsImpl(
    override val languageVersion: String?,
    override val apiVersion: String?,
    override val progressiveMode: Boolean,
    override val enabledLanguageFeatures: Set<String>,
    override val optInAnnotationsInUse: Set<String>,
    override val compilerPluginArguments: List<String>,
    override val compilerPluginClasspath: Set<File>,
    override val freeCompilerArgs: List<String>,
) : KotlinMultiplatformLanguageSettings

internal data class KotlinMultiplatformCompilationImpl(
    override val fullName: String,
    override val platform: String,
    override val konanTarget: String?,
    override val wasmTarget: String?,
    override val declaredSourceSets: Set<String>,
    override val allSourceSets: Set<String>,
    override val compilerArguments: List<String>?,
    override val outputDirs: Set<File>,
    override val archiveFile: File?,
    override val isTest: Boolean,
    override val isManagedByAndroidPlugin: Boolean,
) : KotlinMultiplatformCompilation
