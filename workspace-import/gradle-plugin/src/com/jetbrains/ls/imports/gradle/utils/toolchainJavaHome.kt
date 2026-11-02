// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.utils

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

/** A toolchain JDK: its [home] and its Java language [version]. */
data class ToolchainJdk(val home: File, val version: Int?)

/**
 * The JDK that [javaCompileTask] compiles with, or the project's `java.toolchain` JDK when the task has none; `null`
 * when Gradle cannot tell. The home and the version come from the same compiler, so a task override wins for both.
 *
 * Toolchains exist since Gradle 6.7. This lives in its own file, so an older Gradle never loads the toolchain types:
 * call it only when the toolchain version is set.
 */
fun Project.toolchainJdk(javaCompileTask: Task?): ToolchainJdk? =
    try {
        val compiler = (javaCompileTask as? JavaCompile)?.javaCompiler?.orNull ?: run {
            val toolchain = extensions.findByType(JavaPluginExtension::class.java)?.toolchain
            val service = extensions.findByType(JavaToolchainService::class.java)
            if (toolchain != null && service != null) service.compilerFor(toolchain).orNull else null
        }
        compiler?.metadata?.let { metadata ->
            ToolchainJdk(metadata.installationPath.asFile, metadata.languageVersion.asInt())
        }
    } catch (e: Exception) {
        logger.info("Cannot find the toolchain JDK of $path: ${e.message}")
        null
    } catch (e: LinkageError) {
        logger.info("Cannot find the toolchain JDK of $path: ${e.message}")
        null
    }
