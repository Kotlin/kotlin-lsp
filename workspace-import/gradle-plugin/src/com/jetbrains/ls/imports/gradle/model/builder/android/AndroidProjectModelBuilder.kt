// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.model.AndroidDependency
import com.jetbrains.ls.imports.gradle.model.AndroidProject
import com.jetbrains.ls.imports.gradle.model.impl.AndroidProjectImpl
import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection
import com.jetbrains.ls.imports.gradle.utils.androidComponents
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.tooling.provider.model.ToolingModelBuilder

internal class AndroidProjectModelBuilder : ToolingModelBuilder {
    override fun canBuild(modelName: String): Boolean {
        return modelName == AndroidProject::class.java.name
    }

    override fun buildAll(modelName: String, project: Project): AndroidProject? {
        val variants = project.androidVariants.orEmpty().mapNotNull { it.name }.toSet()
        if (variants.isEmpty()) return null

        val activeVariant = project.androidVariants?.selectActiveVariant()
        val dependencies = project.resolveAndroidDependencies()

        return AndroidProjectImpl(
            buildTreePath = project.buildTreePath,
            activeVariant = activeVariant?.name,
            variants = variants,
            dependencies = dependencies
        )
    }
}

private fun Project.resolveAndroidDependencies(): Set<AndroidDependency> {
    val result = mutableSetOf<AndroidDependency>()
    val activeVariant = androidVariants?.selectActiveVariant() ?: return emptySet()

    result.addAll(resolveAndroidBootClasspathDependencies())
    result.addAll(setOfNotNull(activeVariant.resolveRJarIdeaKotlinDependency()))

    activeVariant.compileConfiguration?.let { main ->
        result.addAll(resolveAndroidDependencies(main))
    }

    activeVariant.nestedComponents.orEmpty().forEach { nested ->
        result.addAll(resolveAndroidDependencies(nested.compileConfiguration ?: return@forEach))
    }

    return result
}

private fun Project.resolveAndroidBootClasspathDependencies(): Set<AndroidDependency> {
    val bootClasspath = androidComponents?.sdkComponents?.bootClasspath?.get() ?: return emptySet()

    return setOf(
        AndroidDependency.Library(
            group = "android",
            name = "android-sdk",
            version = null,
            classpath = bootClasspath.map { it.asFile }
        )
    )
}

/**
 * @see resolveRClassJar
 */
private fun AndroidVariantReflection.resolveRJarIdeaKotlinDependency(): AndroidDependency? {
    val jar = resolveRClassJar() ?: return null
    return AndroidDependency.Library(
        group = "android",
        name = "r",
        version = null,
        classpath = listOf(jar.asFile.orNull ?: return null)
    )
}

/**
 * Resolves all Android based dependencies into the [AndroidDependency] model.
 * 'jar' files are resolved from 'aar' files by using Android's artifact transforms (using "jar" as artifactType)
 */
private fun resolveAndroidDependencies(configuration: Configuration): Set<AndroidDependency> {
    val artifactTypeAttribute = Attribute.of("artifactType", String::class.java)
    return configuration.incoming.artifactView { view ->
        view.isLenient = true
        view.attributes { attributes ->
            attributes.attribute(artifactTypeAttribute, ArtifactTypeDefinition.JAR_TYPE)
        }
    }.resolveDependencies()
}

private fun ArtifactView.resolveDependencies(): Set<AndroidDependency> {
    return artifacts.mapNotNull { artifact ->
        when (val id = artifact.id.componentIdentifier) {
            is ModuleComponentIdentifier -> AndroidDependency.Library(
                group = id.group,
                name = id.module,
                version = id.version,
                classpath = listOf(artifact.file)
            )

            is ProjectComponentIdentifier -> AndroidDependency.ProjectArtifact(
                projectPath = id.projectPath,
                classpath = listOf(artifact.file)
            )

            else -> {
                null
            }
        }
    }.toSet()
}