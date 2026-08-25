// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model

import java.io.Serializable

interface ModuleSourceSets : Serializable {
    val sourceSets: MutableSet<ModuleSourceSet>
    /**
     * The project's published Maven coordinate as `groupId:artifactId:version`, or `null` when the
     * project has no group/version set (and therefore publishes nothing matchable). Used for dependency
     * substitution; mirrors the Maven importer's module coordinate.
     */
    val moduleCoordinate: String?
}
