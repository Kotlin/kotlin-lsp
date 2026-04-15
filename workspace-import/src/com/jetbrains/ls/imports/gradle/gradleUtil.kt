// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import org.gradle.tooling.model.ExternalDependency
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency

fun ExternalDependency.getLibraryName(): String {
    if (gradleModuleVersion != null) {
        return "Gradle: ${gradleModuleVersion.group ?: ""}:${gradleModuleVersion.name ?: ""}:${gradleModuleVersion.version ?: ""}"
    }
    if (file?.name.isNullOrEmpty()) {
        return ""
    }
    return "Gradle: ${file?.name}"
}

fun ModuleSourceSet.isTest(): Boolean = name.lowercase().contains("test")

fun IdeaDependency.isExportedSafe(): Boolean {
    return try {
        when (this) {
            is IdeaSingleEntryLibraryDependency -> isExported
            is IdeaModuleDependency -> exported
            else -> false
        }
    } catch (_: UnsupportedMethodException) {
        false
    }
}

fun <K, V> MutableMap<K, V>.putNotNullValue(key: K, value: V?) {
    if (value != null) {
        put(key, value)
    }
}