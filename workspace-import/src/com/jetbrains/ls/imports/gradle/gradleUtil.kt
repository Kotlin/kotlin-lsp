// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import org.gradle.tooling.model.ExternalDependency
import org.gradle.tooling.model.HierarchicalElement
import org.gradle.tooling.model.idea.IdeaModule

fun ExternalDependency.getLibraryName(): String {
    if (gradleModuleVersion != null) {
        return "Gradle: ${gradleModuleVersion.group ?: ""}:${gradleModuleVersion.name ?: ""}:${gradleModuleVersion.version ?: ""}"
    }
    if (file?.name.isNullOrEmpty()) {
        return ""
    }
    return "Gradle: ${file?.name}"
}

fun IdeaModule.getFqdn(): String {
    var fqdn = name
    if (name == project.name) {
        return name
    }
    var currentParent: HierarchicalElement? = parent
    while (currentParent != null) {
        fqdn = "${currentParent.name}.$fqdn"
        currentParent = currentParent.parent
    }
    return fqdn
}

fun ModuleSourceSet.isTest(): Boolean = name.lowercase().contains("test")