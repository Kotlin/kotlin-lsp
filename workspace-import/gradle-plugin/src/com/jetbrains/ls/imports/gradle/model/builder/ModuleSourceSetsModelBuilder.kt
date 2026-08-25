// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.builder

import com.jetbrains.ls.imports.gradle.model.ModuleJavaSettings
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets
import com.jetbrains.ls.imports.gradle.model.builder.android.resolveAndroidSourceSets
import com.jetbrains.ls.imports.gradle.model.impl.ModuleJavaSettingsImpl
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetImpl
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetsImpl
import com.jetbrains.ls.imports.gradle.utils.KotlinCompilationReflection
import com.jetbrains.ls.imports.gradle.utils.KotlinExtensionReflection
import com.jetbrains.ls.imports.gradle.utils.kotlin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.util.GradleVersion
import java.io.File

private val TARGET_MODEL_NAME: String = ModuleSourceSets::class.java.getName()

class ModuleSourceSetsModelBuilder : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean = TARGET_MODEL_NAME == modelName

    override fun buildAll(modelName: String, project: Project): ModuleSourceSetsImpl? {
        val extensions = project.extensions
        val result = mutableSetOf<ModuleSourceSet>()
        /* Java-based import */
        val sourceSets = extensions.findByType(SourceSetContainer::class.java)
        if (sourceSets != null) {
            result.addAll(readSourceSets(sourceSets, project))
        }
        /* Support for Android-based source sets */
        val androidSourceSets = project.resolveAndroidSourceSets()
        if (androidSourceSets != null) {
            result.addAll(androidSourceSets)
        }
        if (!result.isEmpty()) {
            return ModuleSourceSetsImpl(result, project.moduleCoordinate())
        }
        return null
    }

    /**
     * The project's `groupId:artifactId:version`, or `null` when group/version are not set
     * (e.g. Gradle's default `"unspecified"` version), in which case the project publishes nothing
     * matchable for dependency substitution.
     */
    private fun Project.moduleCoordinate(): String? {
        val group = group.toString()
        val version = version.toString()
        if (group.isEmpty() || version.isEmpty() || "unspecified" == version) {
            return null
        }
        return "$group:${name}:$version"
    }

    private fun readSourceSets(
        sourceSets: SourceSetContainer,
        project: Project
    ): Set<ModuleSourceSet> {
        val result: MutableMap<String, ModuleSourceSet> = HashMap()
        val taskContainer = project.tasks
        val sourceSetRootResolver = GradleSourceSetRootResolver(project)

        for (sourceSet in sourceSets) {
            val sourceSetName = sourceSet.name
            val javaCompileTask = taskContainer.findByName(sourceSet.compileJavaTaskName)
            val runtimeDependencies: Set<File>? = sourceSet.runtimeClasspath.resolveFiles(sourceSetName)
            val compileDependencies: Set<File>? = sourceSet.getCompileClasspath(javaCompileTask).resolveFiles(sourceSetName)

            /* Find kotlin compilation by name and resolve all friend dependencies */
            val friendModuleNames: Set<String> = getFriendModuleNames(project.kotlin, sourceSetName)
            val javaSettings: ModuleJavaSettings = getModuleJavaSettings(project, javaCompileTask)
            val sourceSetRoots = sourceSetRootResolver.resolveSourceSetRoots(sourceSet)

            result[sourceSetName] = ModuleSourceSetImpl(
                name = sourceSetName,
                sources = sourceSetRoots.sourceDirs,
                resources = sourceSetRoots.resourceDirs,
                excludes = sourceSetRoots.excludedPatterns,
                runtimeClasspath = runtimeDependencies ?: emptySet(),
                compileClasspath = compileDependencies ?: emptySet(),
                outputDirs = sourceSetRoots.outputDirs,
                producedArchives = sourceSetRoots.producedArchives,
                friendSourceSets = friendModuleNames,
                hasUnresolvedDependencies = runtimeDependencies == null || compileDependencies == null,
                javaSettings = javaSettings,
                kotlinModule = null
            )
        }

        sourceSetRootResolver.attachUnclaimedRoots(
            result[SourceSet.MAIN_SOURCE_SET_NAME],
            result[SourceSet.TEST_SOURCE_SET_NAME]
        )

        return HashSet<ModuleSourceSet>(result.values)
    }

    private fun getModuleJavaSettings(
        project: Project,
        javaCompileTask: Task?
    ): ModuleJavaSettings {
        val compileOptions: MutableSet<String> = HashSet()
        var sourceCompatibility: String? = null
        var targetCompatibility: String? = null
        val toolchainVersion: Int? = project.getToolchainVersion()
        if (javaCompileTask is JavaCompile) {
            sourceCompatibility = javaCompileTask.sourceCompatibility
            targetCompatibility = javaCompileTask.targetCompatibility
            val javaCompileOptions = javaCompileTask.options
            compileOptions.addAll(javaCompileOptions.getAllCompilerArgs())
        }
        return ModuleJavaSettingsImpl(
            compileOptions,
            toolchainVersion,
            sourceCompatibility,
            targetCompatibility
        )
    }

    private fun Project.getToolchainVersion(): Int? {
        if (GradleVersion.current() >= GradleVersion.version("6.7")) {
            val languageVersionProperty: Property<JavaLanguageVersion>? = extensions
                .findByType(JavaPluginExtension::class.java)
                ?.toolchain
                ?.languageVersion
            if (languageVersionProperty?.isPresent == true) {
                return languageVersionProperty.get().asInt()
            }
        }
        return null
    }

    private fun SourceSet.getCompileClasspath(
        javaCompileTask: Task?
    ): FileCollection {
        var compileClasspath = compileClasspath
        if (javaCompileTask is AbstractCompile) {
            try {
                compileClasspath = javaCompileTask.classpath
            } catch (_: Exception) {
                // ignore
            }
        }
        return compileClasspath
    }

    private fun getFriendModuleNames(kotlin: KotlinExtensionReflection?, sourceSetName: String): Set<String> {
        val kotlinTarget = kotlin?.target
        val kotlinCompilation = kotlinTarget?.getCompilation(sourceSetName)
        val friendModules: Collection<KotlinCompilationReflection> = if (kotlinCompilation != null) {
            kotlinCompilation.allAssociatedCompilations ?: setOf()
        } else {
            setOf()
        }
        return friendModules
            .mapNotNull { it.name }
            .toSet()
    }

    private fun FileCollection.resolveFiles(sourceSetName: String): Set<File>? {
        try {
            return files
        } catch (e: Exception) {
            System.err.println("Unable to resolve a file collection for source set " + sourceSetName + " - " + e.message)
            return null
        }
    }
}
