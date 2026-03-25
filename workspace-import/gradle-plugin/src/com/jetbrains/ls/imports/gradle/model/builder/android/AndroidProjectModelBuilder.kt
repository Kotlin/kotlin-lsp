// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.builder.android

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
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryDependency.Companion.KOTLIN_COMPILE_BINARY_TYPE
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectArtifactDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinProjectCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinSourceDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.artifactsClasspath

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

private fun Project.resolveAndroidDependencies(): Set<IdeaKotlinDependency> {
    val result = mutableSetOf<IdeaKotlinDependency>()
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

private fun Project.resolveAndroidBootClasspathDependencies(): Set<IdeaKotlinDependency> {
    val result = mutableSetOf<IdeaKotlinDependency>()
    val bootClasspath = androidComponents?.sdkComponents?.bootClasspath?.get() ?: return result

    return setOf(
        IdeaKotlinResolvedBinaryDependency(
            binaryType = KOTLIN_COMPILE_BINARY_TYPE,
            classpath = IdeaKotlinClasspath(bootClasspath.map { it.asFile }),
            coordinates = IdeaKotlinBinaryCoordinates("android", "android-sdk", null)
        )
    )
}

/**
 * @see resolveRClassJar
 */
private fun AndroidVariantReflection.resolveRJarIdeaKotlinDependency(): IdeaKotlinDependency? {
    val jar = resolveRClassJar() ?: return null
    return IdeaKotlinResolvedBinaryDependency(
        binaryType = KOTLIN_COMPILE_BINARY_TYPE,
        classpath = IdeaKotlinClasspath(jar.asFile.orNull ?: return null),
        coordinates = IdeaKotlinBinaryCoordinates("android", "r", null)
    )
}

/**
 * Resolves all Android based dependencies into Kotlin's [IdeaKotlinDependency] model.
 * 'jar' files are resolved from 'aar' files by using Android's artifact transforms (using "jar" as artifactType)
 */
private fun resolveAndroidDependencies(configuration: Configuration): Set<IdeaKotlinDependency> {
    val artifactTypeAttribute = Attribute.of("artifactType", String::class.java)
    return configuration.incoming.artifactView { view ->
        view.isLenient = true
        view.attributes { attributes ->
            attributes.attribute(artifactTypeAttribute, ArtifactTypeDefinition.JAR_TYPE)
        }
    }.resolveIdeaKotlinDependencies()
}

private fun ArtifactView.resolveIdeaKotlinDependencies(): Set<IdeaKotlinDependency> {
    return artifacts.mapNotNull { artifact ->
        when (val id = artifact.id.componentIdentifier) {
            is ModuleComponentIdentifier -> IdeaKotlinResolvedBinaryDependency(
                binaryType = KOTLIN_COMPILE_BINARY_TYPE,
                classpath = IdeaKotlinClasspath(artifact.file),
                coordinates = IdeaKotlinBinaryCoordinates(id.group, id.module, id.version)
            )

            is ProjectComponentIdentifier -> IdeaKotlinProjectArtifactDependency(
                type = IdeaKotlinSourceDependency.Type.Regular,
                coordinates = IdeaKotlinProjectCoordinates(
                    buildPath = id.build.buildPath,
                    buildName = if (id.build.buildPath == ":") ":" else id.build.buildPath.split(":").last(),
                    projectPath = id.projectPath,
                    projectName = id.projectName,
                ),
            ).apply {
                artifactsClasspath.add(artifact.file)
            }

            else -> {
                null
            }
        }
    }.toSet()
}