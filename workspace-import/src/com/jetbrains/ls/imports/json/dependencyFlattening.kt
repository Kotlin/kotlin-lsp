// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities

/**
 * See [com.jetbrains.analyzer.bootstrap.AnalyzerOrderEnumerationHandler.shouldProcessDependenciesRecursively]
 *
 * [includeLibraries] also flattens transitively-exported library deps. Bazel routes exported libraries
 * through wrapper modules so it needs this; JPS keeps it off (its libraries are already direct per-module).
 */
fun flattenExportedDependencies(storage: MutableEntityStorage, includeLibraries: Boolean = true) {
    val modules = storage.entities<ModuleEntity>().toList()
    val byName = modules.associateBy { it.name }

    val cache = HashMap<String, ExportedReach>()
    fun reach(name: String): ExportedReach {
        cache[name]?.let { return it }
        cache[name] = ExportedReach(emptySet(), emptySet()) // cycle guard
        val module = byName[name] ?: return cache.getValue(name)
        val reachedModules = LinkedHashSet<String>()
        val reachedLibraries = LinkedHashSet<LibraryId>()
        for (dep in module.dependencies) {
            when (dep) {
                is LibraryDependency -> if (includeLibraries && dep.exported) reachedLibraries.add(dep.library)
                is ModuleDependency -> if (dep.exported) {
                    reachedModules.add(dep.module.name)
                    val sub = reach(dep.module.name)
                    reachedModules.addAll(sub.modules)
                    reachedLibraries.addAll(sub.libraries)
                }
                else -> {}
            }
        }
        return ExportedReach(reachedModules, reachedLibraries).also { cache[name] = it }
    }

    for (module in modules) {
        val presentModules = HashSet<String>()
        val presentLibraries = HashSet<LibraryId>()
        for (dep in module.dependencies) {
            when (dep) {
                is ModuleDependency -> presentModules.add(dep.module.name)
                is LibraryDependency -> presentLibraries.add(dep.library)
                else -> {}
            }
        }
        val newModuleDeps = ArrayList<ModuleDependency>()
        val newLibraryDeps = ArrayList<LibraryDependency>()
        for (dep in module.dependencies) {
            if (dep !is ModuleDependency) continue
            val sub = reach(dep.module.name)
            for (reachedName in sub.modules) {
                if (presentModules.add(reachedName)) {
                    newModuleDeps.add(
                        ModuleDependency(
                            module = ModuleId(reachedName),
                            exported = dep.exported,
                            scope = dep.scope,
                            productionOnTest = dep.productionOnTest,
                        )
                    )
                }
            }
            for (reachedLibrary in sub.libraries) {
                if (presentLibraries.add(reachedLibrary)) {
                    newLibraryDeps.add(
                        LibraryDependency(
                            library = reachedLibrary,
                            exported = dep.exported,
                            scope = dep.scope,
                        )
                    )
                }
            }
        }
        if (newModuleDeps.isEmpty() && newLibraryDeps.isEmpty()) continue
        storage.modifyModuleEntity(module) {
            dependencies += newModuleDeps
            dependencies += newLibraryDeps
        }
    }
}

private data class ExportedReach(val modules: Set<String>, val libraries: Set<LibraryId>)
