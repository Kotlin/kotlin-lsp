// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection
import com.jetbrains.ls.imports.gradle.utils.Reflected
import com.jetbrains.ls.imports.gradle.utils.reflected
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty


/**
 * Android creates access to resources using a class called 'R'.
 * Inside Android Studio, this access is synthetic. We, however, do rely on resolving this jar, once built.
 */
@Suppress("UNCHECKED_CAST")
internal fun AndroidVariantReflection.resolveRClassJar(): RegularFileProperty? {
    val androidClassLoader = reflected.instance.javaClass.classLoader
    val taskClassName = "com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask"
    val taskClass = runCatching { androidClassLoader.loadClass(taskClassName) }.getOrNull() ?: return null
    taskClass as Class<Task>
    return project.tasks.withType(taskClass).map { LinkApplicationAndroidResourcesTaskReflection(project, it.reflected) }
        .firstOrNull { task -> task.variantName != null && task.variantName == this@resolveRClassJar.name }
        ?.rClassOutputJar
}

private class LinkApplicationAndroidResourcesTaskReflection(val project: Project, val reflected: Reflected.Instance) {
    val variantName: String? by lazy {
        reflected.call("getVariantName")?.unwrapAs<String>() ?: run {
            project.logger.error("Failed to get 'variantName'")
            return@lazy null
        }
    }

    val rClassOutputJar: RegularFileProperty? by lazy {
        reflected.call("getRClassOutputJar")?.unwrapAs<RegularFileProperty>() ?: run {
            project.logger.error("Failed to get 'rClassOutputJar'")
            return@lazy null
        }
    }
}
