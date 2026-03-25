// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.compile.JavaCompile

val Project.kotlin: KotlinExtensionReflection?
    get() {
        val kotlin = extensions.findByName("kotlin") ?: return null
        return KotlinExtensionReflection(this, kotlin.reflected)
    }

class KotlinExtensionReflection(private val project: Project, val reflected: Reflected.Instance) {
    val target: KotlinTargetExtensionReflection? by lazy {
        val target = reflected.call("getTarget") ?: run {
            this.project.logger.error("Failed to find 'target' in Kotlin extension")
            return@lazy null
        }

        KotlinTargetExtensionReflection(project, target)
    }
}

class KotlinTargetExtensionReflection(val project: Project, val reflected: Reflected.Instance) {
    fun getCompilation(name: String): KotlinCompilationReflection? {
        val compilation = reflected.call("getCompilations")?.call("findByName", param(name)) ?: run {
            project.logger.error("Failed to find compilation '$name' in Kotlin target extension")
            return null
        }

        return KotlinCompilationReflection(project, compilation)
    }
}

class KotlinCompilationReflection(val project: Project, val reflected: Reflected.Instance) {

    val compileTask: KotlinCompileTaskReflection? by lazy {
        val compileTask = reflected.call("getCompileTaskProvider")?.call("get") ?: run {
            project.logger.error("Failed to resolve 'compileTask' in Kotlin compilation")
            return@lazy null
        }

        KotlinCompileTaskReflection(project, compileTask)
    }

    val javaCompileTask: JavaCompile? by lazy {
        val javaCompileTask = reflected.call("getCompileJavaTaskProvider")?.call("get") ?: run {
            project.logger.error("Failed to resolve 'javaCompileTask' in Kotlin compilation")
            return@lazy null
        }

        javaCompileTask.unwrapAs<JavaCompile>()
    }
}

class KotlinCompileTaskReflection(val project: Project, val reflected: Reflected.Instance) {
    val libraries: FileCollection? by lazy {
        reflected.call("getLibraries")?.unwrapAs<FileCollection>() ?: run {
            project.logger.error("Failed to resolve 'libraries' in Kotlin compile task")
            return@lazy null
        }
    }
}
