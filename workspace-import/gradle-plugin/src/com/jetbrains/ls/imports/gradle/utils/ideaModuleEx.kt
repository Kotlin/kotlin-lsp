// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.utils

import org.gradle.plugins.ide.idea.model.IdeaModule
import org.gradle.util.GradleVersion
import java.io.File

private val currentGradleVersion = GradleVersion.current().baseVersion
private val is47OrBetter = isCurrentGradleAtLeast("4.7")
private val is74OrBetter = isCurrentGradleAtLeast("7.4")

fun IdeaModule.getResourceDirsSafe(): Set<File> {
    if (is47OrBetter) {
        return this.reflected.call("getResourceDirs")?.unwrapAs<Set<File>>() ?: emptySet()
    }
    return emptySet()
}

fun IdeaModule.getTestSourcesSafe(): Set<File> {
    if (is74OrBetter) {
        return testSources.files
    }
    // getTestResourceDirs was removed in Gradle 9.0
    return this.reflected.call("getTestSourceDirs")?.unwrapAs<Set<File>>() ?: emptySet()
}

fun IdeaModule.getTestResourcesSafe(): Set<File> {
    if (is74OrBetter) {
        return testResources.files
    }
    return this.reflected.call("getTestResourceDirs")?.unwrapAs<Set<File>>() ?: emptySet()
}

fun IdeaModule.getGeneratedSourceDirsSafe(): Set<File> {
    return this.reflected.call("getGeneratedSourceDirs")?.unwrapAs<Set<File>>() ?: emptySet()
}

private fun isCurrentGradleAtLeast(version: String): Boolean {
    return currentGradleVersion >= GradleVersion.version(version)
}
