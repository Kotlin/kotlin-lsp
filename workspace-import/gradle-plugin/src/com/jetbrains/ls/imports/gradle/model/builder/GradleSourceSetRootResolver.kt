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

        // The source set's compiled-output directories (class dirs + resources dir) and its packaged archives
        // (jar/war/ear/...) are kept apart: only the directories belong on a run classpath, while the archives are
        // used solely to map a dependency on such an archive back to its producing module.
        val outputDirs: Set<File> = sourceSet.output.files
        val producedArchives: Set<File> = SourceSetArtifactExtractor.extractSourceSetArtifacts(project, sourceSet).toSet()

        return SourceSetRoots(
            sourceDirs,
            resourceDirs,
            excludes,
            outputDirs,
            producedArchives
        )
    }

    data class SourceSetRoots(
        val sourceDirs: Set<File>,
        val resourceDirs: Set<File>,
        val excludedPatterns: Set<String>,
        val outputDirs: Set<File>,
        val producedArchives: Set<File>
    )

    companion object {
        private fun getIdeaModule(project: Project): IdeaModule? {
            val plugins = project.plugins
            val ideaPlugin = plugins.findPlugin<IdeaPlugin>(IdeaPlugin::class.java)
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
