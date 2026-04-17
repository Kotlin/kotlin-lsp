// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.builder

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.gradle.utils.SourceSetArtifactExtractor
import com.jetbrains.ls.imports.gradle.utils.getGeneratedSourceDirsSafe
import com.jetbrains.ls.imports.gradle.utils.getResourceDirsSafe
import com.jetbrains.ls.imports.gradle.utils.getTestResourcesSafe
import com.jetbrains.ls.imports.gradle.utils.getTestSourcesSafe
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModule
import java.io.File

class GradleSourceSetRootResolver(private val project: Project) {

    private val ideaSourceDirs: MutableSet<File> = mutableSetOf()
    private val ideaTestSourceDirs: MutableSet<File> = mutableSetOf()
    private val ideaResourceDirs: MutableSet<File> = mutableSetOf()
    private val ideaTestResourceDirs: MutableSet<File> = mutableSetOf()
    private val ideaGeneratedSourceDirs: MutableSet<File> = mutableSetOf()

    init {
        val ideaPluginModule: IdeaModule? = getIdeaModule(project)
        if (ideaPluginModule != null) {
            this.ideaSourceDirs.addAll(ideaPluginModule.sourceDirs)
            this.ideaTestSourceDirs.addAll(ideaPluginModule.getTestSourcesSafe())

            this.ideaResourceDirs.addAll(ideaPluginModule.getResourceDirsSafe())
            this.ideaTestResourceDirs.addAll(ideaPluginModule.getTestResourcesSafe())

            this.ideaGeneratedSourceDirs.addAll(ideaPluginModule.getGeneratedSourceDirsSafe())
        }
    }

    fun attachUnclaimedRoots(main: ModuleSourceSet?, test: ModuleSourceSet?) {
        if (main != null) {
            main.getSources().apply {
                addAll(ideaSourceDirs)
                addAll(ideaGeneratedSourceDirs)
            }
            main.getResources().addAll(ideaResourceDirs)
        }
        if (test != null) {
            test.getSources().addAll(ideaTestSourceDirs)
            test.getResources().addAll(ideaTestResourceDirs)
        }
    }

    fun resolveSourceSetRoots(sourceSet: SourceSet): SourceSetRoots {
        val sources = sourceSet.allJava
        val sourceDirs = mutableSetOf<File>()
        sourceDirs.addAll(sources.srcDirs)
        ideaSourceDirs.removeAll(sourceDirs)
        ideaTestSourceDirs.removeAll(sourceDirs)
        ideaGeneratedSourceDirs.removeAll(sourceDirs)

        val resources = sourceSet.resources
        val resourceDirs = mutableSetOf<File>()
        resourceDirs.addAll(resources.srcDirs)
        ideaResourceDirs.removeAll(resourceDirs)
        ideaTestResourceDirs.removeAll(resourceDirs)

        val excludes = HashSet<String>()
        excludes.addAll(sources.excludes)
        excludes.addAll(resources.excludes)

        val producedArtifacts: MutableSet<File> = mutableSetOf()
        producedArtifacts.addAll(sourceSet.output.files)
        producedArtifacts.addAll(SourceSetArtifactExtractor.extractSourceSetArtifacts(project, sourceSet))

        return SourceSetRoots(
            sourceDirs,
            resourceDirs,
            excludes,
            producedArtifacts
        )
    }

    data class SourceSetRoots(
        val sourceDirs: Set<File>,
        val resourceDirs: Set<File>,
        val excludedPatterns: Set<String>,
        val producedArtifacts: Set<File>
    )

    companion object {
        private fun getIdeaModule(project: Project): IdeaModule? {
            val plugins = project.plugins
            val ideaPlugin = plugins.findPlugin<IdeaPlugin?>(IdeaPlugin::class.java)
            if (ideaPlugin != null) {
                val ideaPluginModel = ideaPlugin.model
                if (ideaPluginModel != null) {
                    return ideaPluginModel.module
                }
            }
            return null
        }
    }
}
