// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")
@file:JvmName("AndroidSourceSets")

package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.gradle.model.builder.KotlinMetadataModelBuilder
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetImpl
import com.jetbrains.ls.imports.gradle.utils.AndroidComponentReflection
import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinCompileTaskReflection
import com.jetbrains.ls.imports.gradle.utils.androidComponents
import com.jetbrains.ls.imports.gradle.utils.kotlin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File
import kotlin.collections.map
import kotlin.collections.orEmpty
import kotlin.collections.plus

fun Project.resolveAndroidSourceSets(): Set<ModuleSourceSet>? {
    if (!isAndroidProject()) return null
    val androidVariants = androidVariants ?: return null
    val activeVariant = androidVariants.selectActiveVariant() ?: return null
    return resolveAndroidVariant(activeVariant)
}

private fun resolveAndroidVariant(variant: AndroidVariantReflection): Set<ModuleSourceSet>? {
    val main = variant.resolveToModuleSourceSet() ?: return null
    val nested = variant.nestedComponents.orEmpty()
        .mapNotNull { nested -> nested.resolveToModuleSourceSet() }

    return setOf(main, *nested.toTypedArray())
}

private fun AndroidComponentReflection.resolveToModuleSourceSet(): ModuleSourceSet? {
    val mainComponent = project.androidVariants?.selectActiveVariant() ?: return null

    val name = name ?: return null
    val kotlinCompilation = project.kotlin?.target?.getCompilation(name)
    val kotlinCompileTask = kotlinCompilation?.compileTask
    val javaCompileTask = kotlinCompilation?.javaCompileTask
    val javaExtension = project.extensions.findByType(JavaPluginExtension::class.java)

    val bootClasspath = project.androidComponents?.sdkComponents?.bootClasspath?.get()
        .orEmpty().map { it.asFile }.toSet()

    val rClassJar = if (this is AndroidVariantReflection) setOfNotNull(resolveRClassJar()?.asFile?.orNull) else emptySet()

    val compileClasspath: Set<File> = run {
        (compileConfiguration ?: return@run emptySet()).incoming.artifactView { view ->
            view.isLenient = true
            view.attributes { attributes ->
                attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
            }
        }.files.files
    }

    return ModuleSourceSetImpl(
        /* name = */ name,
        /* sources = */ sources?.kotlin?.get().orEmpty().map { it.asFile }.toSet() +
                sources?.java?.get().orEmpty().map { it.asFile }.toSet(),
        /* resources = */sources?.resources?.get().orEmpty().map { it.asFile }.toSet(),
        /* excludes = */emptySet(),
        /* runtimeClasspath = */emptySet(),
        /* compileClasspath = */bootClasspath + rClassJar + compileClasspath,
        /* sourceSetOutput = */setOfNotNull(),
        /* friendSourceSets =*/  kotlinCompilation?.allAssociatedCompilations?.mapNotNull { it.name }.orEmpty().toSet() +
                listOfNotNull(mainComponent.name.takeIf { it != name }),
        /* hasUnresolvedDependencies = */false,
        /* toolchainVersion = */javaExtension?.toolchain?.languageVersion?.orNull?.asInt(),
        /* sourceCompatibility = */javaCompileTask?.sourceCompatibility,
        /* targetCompatibility = */javaCompileTask?.targetCompatibility,
        /* kotlinModule =*/ kotlinCompileTask?.resolveKotlinModule()
    )
}

private fun KotlinCompileTaskReflection.resolveKotlinModule(): KotlinModule? {
    val instance = reflected.instance as? Task ?: return null
    return KotlinMetadataModelBuilder.readCompilerSettings(instance)
}