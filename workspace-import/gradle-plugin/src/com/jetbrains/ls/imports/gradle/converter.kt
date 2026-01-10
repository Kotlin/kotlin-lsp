// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE", "MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")

package com.jetbrains.ls.imports.gradle

import kotlinx.serialization.json.Json
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.plugins.ide.internal.tooling.GradleProjectBuilder
import org.gradle.plugins.ide.internal.tooling.IdeaModelBuilder
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaModuleDependency
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaSingleEntryLibraryDependency
import java.io.File
import java.nio.file.Path

private val KOTLIN_COMPILER_PLUGIN_JAR_PATTERN = Regex(
    ".*-compiler-plugin.*\\.jar"
)

fun Gradle.toWorkspaceData(): WorkspaceData {
    val modules = mutableListOf<ModuleData>()
    val librariesMap = mutableMapOf<String, LibraryData>()
    val kotlinSettings = mutableListOf<KotlinSettingsData>()

    // TODO IdeaProject dependency order is special, use it temporarily
    val ideaProject = IdeaModelBuilder(GradleProjectBuilder())
        .buildForRoot(gradle.rootProject, false)
    val ideaModules = ideaProject.modules.associateBy { it.name }

    // Build project map for inter-project dependencies
    val projectMap = mutableMapOf<String, Project>()
    gradle.rootProject.allprojects { p ->
        projectMap["${p.group}:${p.name}"] = p
    }

    gradle.rootProject.allprojects { project ->
        val sourceSetContainer = project.extensions.findByType(SourceSetContainer::class.java)
        listOf("main", "test").forEach { sourceSetName ->
            val sourceSet = sourceSetContainer?.getByName(sourceSetName)
            val moduleName = "${project.name}.$sourceSetName"

            // Add content roots
            val sourceRoots = mutableListOf<SourceRootData>()
            val excludedUrls = mutableListOf<String>()

            // Collect all source directories (Java + Kotlin)
            val allSourceDirs = mutableSetOf<Path>()
            val resourceDirs = mutableSetOf<Path>()

            if (sourceSet != null) {
                sourceSet.java.srcDirs.forEach { dir ->
                    allSourceDirs.add(dir.toPath().toAbsolutePath())
                }
                sourceSet.resources.srcDirs.forEach { dir ->
                    resourceDirs.add(dir.toPath().toAbsolutePath())
                }

                // Try to get Kotlin source directories if Kotlin plugin is applied
                if (project.plugins.hasPlugin("org.jetbrains.kotlin.jvm") ||
                    project.plugins.hasPlugin("kotlin")
                ) {
                    // For Kotlin projects, get kotlin source directories
                    val kotlinSourceSet = sourceSet.extensions.findByName("kotlin") as? SourceDirectorySet
                    kotlinSourceSet?.srcDirs?.forEach { dir ->
                        allSourceDirs.add(dir.toPath().toAbsolutePath())
                    }
                }
            }

            if (sourceSetName == "main") {
                allSourceDirs.forEach { path ->
                    sourceRoots.add(SourceRootData(path = path.toString(), type = "java-source"))
                }
                resourceDirs.forEach { path ->
                    sourceRoots.add(SourceRootData(path = path.toString(), type = "java-resource"))
                }
            } else {
                allSourceDirs.forEach { path ->
                    sourceRoots.add(SourceRootData(path = path.toString(), type = "java-test"))
                }
                resourceDirs.forEach { path ->
                    sourceRoots.add(SourceRootData(path = path.toString(), type = "java-test-resource"))
                }
            }

            val contentRoot = ContentRootData(
                path = project.projectDir.absolutePath,
                excludedPatterns = emptyList(),
                excludedUrls = excludedUrls,
                sourceRoots = sourceRoots
            )

            val moduleData = ModuleData(
                name = moduleName,
                type = "JAVA_MODULE",
                dependencies = buildList {
                    ideaModules[project.name]!!.dependencies.forEach { dependency ->
                        val scope = when (dependency) {
                            is DefaultIdeaSingleEntryLibraryDependency -> dependency.scope
                            is DefaultIdeaModuleDependency -> dependency.scope
                            else -> return@forEach
                        }

                        if (sourceSetName == "main" && scope.scope == "TEST") return@forEach
                        when (dependency) {
                            is DefaultIdeaSingleEntryLibraryDependency -> {
                                val gav = dependency.gradleModuleVersion
                                val libName = gav?.run {
                                    "Gradle: ${group}:${name}${
                                        if (version.isNotEmpty()) ":${version}" else ""
                                    }"
                                } ?: return@forEach
                                if (!librariesMap.containsKey(libName)) {
                                    librariesMap[libName] = LibraryData(
                                        name = libName,
                                        level = "project",
                                        module = null,
                                        type = "java-imported",
                                        roots = buildList {
                                            dependency.file?.run {
                                                add(
                                                    LibraryRootData(
                                                        path = absolutePath,
                                                        type = "CLASSES",
                                                    )
                                                )
                                            }
                                            dependency.source?.run {
                                                add(
                                                    LibraryRootData(
                                                        path = absolutePath,
                                                        type = "SOURCES",
                                                    )
                                                )
                                            }
                                        },
                                        excludedRoots = emptyList(),
                                        properties = XmlElement(
                                            tag = "properties",
                                            attributes = linkedMapOf(
                                                "groupId" to gav.group,
                                                "artifactId" to gav.name,
                                                "version" to gav.version,
                                                "baseVersion" to gav.version,
                                            ),
                                            children = emptyList(),
                                            text = null
                                        )
                                    )
                                }
                                add(
                                    DependencyData.Library(
                                        name = libName,
                                        scope = DependencyDataScope.valueOf(dependency.scope.scope),
                                        isExported = dependency.exported,
                                    )
                                )
                            }

                            is DefaultIdeaModuleDependency -> add(
                                DependencyData.Module(
                                    name = "${dependency.targetModuleName}.main",
                                    scope = DependencyDataScope.valueOf(dependency.scope.scope),
                                    isExported = dependency.exported
                                )
                            )

                            else -> Unit
                        }
                    }
                    add(DependencyData.ModuleSource)
                    add(DependencyData.InheritedSdk)
                    if (sourceSetName == "test") {
                        add(
                            DependencyData.Module(
                                name = "${project.name}.main",
                                scope = DependencyDataScope.COMPILE,
                                isExported = false
                            )
                        )
                    }
                },
                contentRoots = listOf(contentRoot),
                facets = emptyList()
            )

            modules.add(moduleData)

            // Extract Kotlin settings if Kotlin plugin is applied
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.jvm") ||
                project.plugins.hasPlugin("org.jetbrains.kotlin.android") ||
                project.plugins.hasPlugin("kotlin") ||
                project.plugins.hasPlugin("kotlin-android")
            ) {
                val kotlinExtension = project.extensions.findByName("kotlin")

                if (kotlinExtension != null) {
                    val sourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName(sourceSetName)
                    val moduleName = "${project.name}.$sourceSetName"

                    // Collect source roots
                    val sourceRoots = mutableListOf<String>()
                    sourceSet.allSource.srcDirs.forEach { dir ->
                        val path = dir.toPath().toAbsolutePath()
                        sourceRoots.add(path.toString())
                    }

                    // Get Kotlin compiler options
                    var jvmTarget: String? = null
                    val compilerArgs = mutableListOf<String>()

                    val compileTask = project.tasks.findByName("compileKotlin")
                    compileTask?.property("kotlinOptions")?.let { kotlinOptions ->
                        kotlinOptions.genericGet<String>("getJvmTarget")?.let {
                            jvmTarget = it
                        }
                        kotlinOptions.genericGet<List<String>>("getFreeCompilerArgs")?.let {
                            compilerArgs.addAll(it)
                        }
                    }

                    // Detect kotlinx.serialization plugin and add plugin options
                    val pluginOptions = mutableListOf<String>()
                    val pluginClasspaths = mutableListOf<String>()

                    compileTask
                        ?.genericGet<Any>("getPluginClasspath")
                        ?.genericGet<Collection<File>>("getFiles")
                        ?.forEach { file ->
                            if (file.name.matches(KOTLIN_COMPILER_PLUGIN_JAR_PATTERN)) {
                                pluginClasspaths.add(file.absolutePath)
                            }
                        }

                    // Try to extract plugin options from compile task
                    compileTask
                        ?.genericGet<Any>("getPluginOptions")
                        ?.genericGet<List<String>>("getArguments")
                        ?.forEach { arg ->
                            pluginOptions.add(arg)
                        }

                    val compilerArguments = KotlinJvmCompilerArguments(
                        jvmTarget = jvmTarget,
                        pluginOptions = pluginOptions,
                        pluginClasspaths = pluginClasspaths
                    )

                    val kotlinSettingsData = KotlinSettingsData(
                        name = "Kotlin",
                        sourceRoots = sourceRoots,
                        configFileItems = emptyList(),
                        module = moduleName,
                        useProjectSettings = false,
                        implementedModuleNames = emptyList(),
                        dependsOnModuleNames = emptyList(),
                        additionalVisibleModuleNames = if (sourceSetName == "test") setOf("${project.name}.main") else emptySet(),
                        productionOutputPath = if (sourceSetName == "main") sourceSet.output.classesDirs.asPath else null,
                        testOutputPath = if (sourceSetName == "test") sourceSet.output.classesDirs.asPath else null,
                        sourceSetNames = emptyList(),
                        isTestModule = sourceSetName == "test",
                        externalProjectId = "${project.group}:${project.name}:${project.version}",
                        isHmppEnabled = true,
                        pureKotlinSourceFolders = emptyList(),
                        kind = KotlinSettingsData.KotlinModuleKind.DEFAULT,
                        compilerArguments = "J${Json.encodeToString(compilerArguments)}",
                        additionalArguments = compilerArgs.joinToString(" "),
                        scriptTemplates = null,
                        scriptTemplatesClasspath = null,
                        copyJsLibraryFiles = false,
                        outputDirectoryForJsLibraryFiles = null,
                        targetPlatform = null,
                        externalSystemRunTasks = emptyList(),
                        version = 5,
                        flushNeeded = false
                    )

                    kotlinSettings.add(kotlinSettingsData)
                }
            }
        }
    }

    return WorkspaceData(
        modules = modules,
        libraries = librariesMap.values.toList(),
        sdks = emptyList(),
        kotlinSettings = kotlinSettings
    )
}

private inline fun <reified T> Any.genericGet(name: String): T? =
    try {
        javaClass.getMethod(name).invoke(this) as? T
    } catch (_: Throwable) {
        null
    }
