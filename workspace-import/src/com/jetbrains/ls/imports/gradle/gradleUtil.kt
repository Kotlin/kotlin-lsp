// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import org.gradle.tooling.model.ExternalDependency

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