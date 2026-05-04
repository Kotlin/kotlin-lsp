// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.util

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.getLibraryName
import com.jetbrains.ls.imports.gradle.isExportedSafe
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File

private val LOG = logger<DependencyFileIndex>()

internal class DependencyFileIndex {

    private val index: MutableMap<File, MutableSet<DependencyData>> = HashMap()

    fun get(file: File): Set<DependencyData> {
        return index[file] ?: emptySet()
    }

    fun get(file: File, scopeCalculator: DependencyDataScopeCalculator): List<DependencyData> {
        val dependencies = index[file] ?: run {
            LOG.warn("Unresolved dependency file $file")
            return emptyList()
        }
        return dependencies.mapNotNull { data ->
            if (data is DependencyData.Library) {
                return@mapNotNull DependencyData.Library(data.name, scopeCalculator.getScope(file, data), data.isExported)
            }
            if (data is DependencyData.Module) {
                return@mapNotNull DependencyData.Module(data.name, scopeCalculator.getScope(file, data), data.isExported, data.isTestJar)
            }
            LOG.warn("Unexpected dependency type of $file")
            return@mapNotNull null
        }
    }

    fun add(file: File, dependencyData: DependencyData) {
        storage(file) {
            add(dependencyData)
        }
    }

    fun add(dependency: IdeaSingleEntryLibraryDependency) {
        storage(dependency.file) {
            add(
                DependencyData.Library(
                    dependency.getLibraryName(),
                    DependencyDataScope.RUNTIME,
                    dependency.isExportedSafe()
                )
            )
        }
    }

    fun add(moduleFqn: String, sourceSet: ModuleSourceSet) {
        for (producedArtifact in sourceSet.sourceSetOutput) {
            storage(producedArtifact) {
                add(
                    DependencyData.Module(
                        name = "$moduleFqn.${sourceSet.name}",
                        scope = DependencyDataScope.RUNTIME,
                        isExported = false,
                        isTestJar = sourceSet.name.lowercase().contains("test")
                    )
                )
            }
        }
    }

    fun add(dependency: ExternalModuleDependency) {
        storage(dependency.file) {
            add(
                DependencyData.Library(
                    "Gradle: ${dependency.mavenCoordinates}",
                    DependencyDataScope.RUNTIME,
                    false
                )
            )
        }
    }

    private fun storage(file: File, action: MutableSet<DependencyData>.() -> Unit) {
        action(index.computeIfAbsent(file) {
            mutableSetOf()
        })
    }
}
