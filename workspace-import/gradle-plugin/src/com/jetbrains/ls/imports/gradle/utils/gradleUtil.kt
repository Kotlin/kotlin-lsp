// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils

import org.gradle.tooling.BuildController
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.util.GradleVersion

private val INCLUDED_BUILD_API_GRADLE_VERSION = GradleVersion.version("8.0")

fun BuildController.getIncludedBuilds(): DomainObjectSet<out GradleBuild> {
    if (GradleVersion.current() <= INCLUDED_BUILD_API_GRADLE_VERSION) {
        return buildModel.includedBuilds
    }
    val editableBuilds = buildModel.editableBuilds
    if (editableBuilds.isEmpty()) {
        return buildModel.includedBuilds
    }
    return editableBuilds
}
