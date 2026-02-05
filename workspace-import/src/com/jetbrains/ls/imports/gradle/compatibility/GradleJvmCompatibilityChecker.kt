// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.compatibility

import com.intellij.util.lang.JavaVersion
import org.gradle.util.GradleVersion

/**
 * Todo: Replace with GradleJvmSupportMatrix.
 */
object GradleJvmCompatibilityChecker {

    private var compatibility: List<Pair<Ranges<JavaVersion>, Ranges<GradleVersion>>> = getCompatibilityRanges()

    fun isSupported(gradleVersion: GradleVersion, javaVersion: JavaVersion): Boolean {
        return compatibility.any { (javaVersions, gradleVersions) ->
            javaVersion in javaVersions && gradleVersion.baseVersion in gradleVersions
        }
    }

    private fun getCompatibilityRanges(): List<Pair<Ranges<JavaVersion>, Ranges<GradleVersion>>> {
        return DEFAULT_DATA.mapNotNull { entry ->
            val gradleVersionInfo = entry.gradle
            val javaVersionInfo = entry.java
            val gradleRange = parseRange(
                gradleVersionInfo.split(','),
                GradleVersion::version
            )
            val javaRange = runCatching {
                parseRange(
                    javaVersionInfo.split(','),
                    JavaVersion.Companion::parse
                )
            }.getOrNull() ?: return@mapNotNull null
            javaRange to gradleRange
        }
    }
}
