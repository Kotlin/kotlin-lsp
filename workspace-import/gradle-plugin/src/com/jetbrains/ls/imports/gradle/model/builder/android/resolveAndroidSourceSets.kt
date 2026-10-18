// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")
@file:JvmName("AndroidSourceSets")

package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.gradle.model.builder.KotlinMetadataModelBuilder
import com.jetbrains.ls.imports.gradle.model.impl.ModuleJavaSettingsImpl
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

    // The plain arguments and each argument provider on its own. A provider is code of the Android plugin, and the
    // Room one reads the annotation processor list that `javaPreCompile<Variant>` writes; on a checkout nothing has
    // built, that is a FileNotFoundException, and reading `allCompilerArgs` turned it into a stack trace per variant
    // in the import log. The arguments such a provider holds exist only after a build, so it contributes nothing and
    // the import log says so in one line.
    val compileOptions = buildSet {
        javaCompileTask?.options?.compilerArgs?.let(::addAll)
        javaCompileTask?.options?.compilerArgumentProviders.orEmpty().forEach { provider ->
            try {
                addAll(provider.asArguments())
            } catch (e: Exception) {
                project.logger.info(
                    "Compiler arguments of ${provider.javaClass.name} for module $name are not available before a build: ${e.message}"
                )
            }
        }
    }
    val javaSettings = ModuleJavaSettingsImpl(
        compileOptions = compileOptions,
        toolchainVersion = javaExtension?.toolchain?.languageVersion?.orNull?.asInt(),
        sourceCompatibility = javaCompileTask?.sourceCompatibility,
        targetCompatibility = javaCompileTask?.targetCompatibility
    )

    return ModuleSourceSetImpl(
        /* name = */ name,
        /* sources = */ buildMutableSet {
            addAll(sources?.kotlin?.get().orEmpty().map { it.asFile })
            addAll(sources?.java?.get().orEmpty().map { it.asFile })
        },
        /* resources = */sources?.resources?.get().orEmpty().map { it.asFile }.toMutableSet(),
        /* excludes = */emptySet(),
        /* runtimeClasspath = */emptySet(),
        /* compileClasspath = */buildSet {
            addAll(bootClasspath)
            addAll(rClassJar)
            addAll(compileClasspath)
        },
        /* outputDirs = */emptySet(),
        /* producedArchives = */emptySet(),
        /* friendSourceSets =*/  kotlinCompilation?.allAssociatedCompilations?.mapNotNull { it.name }.orEmpty().toSet() +
                listOfNotNull(mainComponent.name.takeIf { it != name }),
        /* hasUnresolvedDependencies = */false,
        /* javaSettings = */ javaSettings,
        /* kotlinModule =*/ kotlinCompileTask?.resolveKotlinModule()
    )
}

private fun <T> buildMutableSet(builderAction: MutableSet<T>.() -> Unit): MutableSet<T> {
    val set = mutableSetOf<T>()
    builderAction(set)
    return set
}

private fun KotlinCompileTaskReflection.resolveKotlinModule(): KotlinModule? {
    val instance = reflected.instance as? Task ?: return null
    return KotlinMetadataModelBuilder.readCompilerSettings(instance)
}