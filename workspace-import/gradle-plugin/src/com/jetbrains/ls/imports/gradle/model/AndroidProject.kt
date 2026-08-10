// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model

import java.io.File
import java.io.Serializable

interface AndroidProject : Serializable {
    val buildTreePath: String
    val activeVariant: String?
    val variants: Set<String>
    val dependencies: Set<AndroidDependency>
}

/**
 * ponytail: own plain model instead of Kotlin's `IdeaKotlinDependency`.
 * The KGP classes are bundled by several IDE plugins, so the deserialized model and the consumer
 * ended up with copies from different `PluginClassLoader`s (ClassCastException on read).
 */
sealed interface AndroidDependency : Serializable {
    val classpath: List<File>

    data class Library(
        val group: String?,
        val name: String,
        val version: String?,
        override val classpath: List<File>,
    ) : AndroidDependency

    data class ProjectArtifact(
        val projectPath: String,
        override val classpath: List<File>,
    ) : AndroidDependency
}
