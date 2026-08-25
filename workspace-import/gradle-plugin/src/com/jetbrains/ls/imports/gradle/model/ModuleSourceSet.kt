// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model

import java.io.File
import java.io.Serializable

interface ModuleSourceSet : Serializable {
    val name: String
    val sources: MutableSet<File>
    val resources: MutableSet<File>
    val excludes: Set<String>
    val runtimeClasspath: Set<File>
    val compileClasspath: Set<File>
    /**
     * The source set's compiled-output directories: the class dirs (one per language, e.g.
     * `build/classes/java/main`, `build/classes/kotlin/main`) and the resources dir. These are what a
     * run of the module should have on its classpath, since they always reflect the current compiled state.
     */
    val outputDirs: Set<File>
    /**
     * The packaged archives this source set produces (the jar/war/ear/... files emitted by its archive tasks).
     * Kept separate from [.getOutputDirs] because they must not go on the run classpath (a package may be
     * stale or unbuilt); they are used only to map a dependency on such an archive back to its producing module.
     */
    val producedArchives: Set<File>
    /**
     * @return names of source sets (compilations) which are considered friends
     * 'friends' are allowed to use 'internal' declarations from other models.
     * Kotlin defines such friends using an 'associateWith' declaration between compilations.
     */
    val friendSourceSets: Set<String>
    val hasUnresolvedDependencies: Boolean
    val javaSettings: ModuleJavaSettings
    /**
     * @return A dedicated module if directly associated with the source set.
     * Note: This might return null, relying on a 'project level' KotlinModule to be provided
     */
    val kotlinModule: KotlinModule?
}
