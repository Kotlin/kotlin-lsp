// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.util

import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import java.io.File

interface DependencyDataScopeCalculator {

    fun getScope(file: File, dependencyData: DependencyData): DependencyDataScope

    companion object {
        fun forProvided(
            compileDependencies: Set<File>,
            compileModules: List<String>
        ): DependencyDataScopeCalculator {
            return object : DependencyDataScopeCalculator {
                override fun getScope(file: File, dependencyData: DependencyData): DependencyDataScope {
                    return if (dependencyData is DependencyData.Module) {
                        when {
                            compileModules.contains(dependencyData.name) -> DependencyDataScope.COMPILE
                            else -> DependencyDataScope.PROVIDED
                        }
                    } else {
                        when {
                            compileDependencies.contains(file) -> DependencyDataScope.COMPILE
                            else -> DependencyDataScope.PROVIDED
                        }
                    }
                }
            }
        }

        fun forRuntime(
            compileDependencies: Set<File>,
            compileModules: List<String>
        ): DependencyDataScopeCalculator {
            return object : DependencyDataScopeCalculator {
                override fun getScope(file: File, dependencyData: DependencyData): DependencyDataScope {
                    return if (dependencyData is DependencyData.Module) {
                        when {
                            compileModules.contains(dependencyData.name) -> DependencyDataScope.COMPILE
                            else -> DependencyDataScope.RUNTIME
                        }
                    } else {
                        when {
                            compileDependencies.contains(file) -> DependencyDataScope.COMPILE
                            else -> DependencyDataScope.RUNTIME
                        }
                    }
                }
            }
        }
    }
}
