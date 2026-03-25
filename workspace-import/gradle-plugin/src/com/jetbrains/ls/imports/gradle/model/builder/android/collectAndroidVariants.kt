@file:JvmName("AndroidVariants")

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection
import com.jetbrains.ls.imports.gradle.utils.androidComponents
import com.jetbrains.ls.imports.gradle.utils.extras
import org.gradle.api.Project
import org.gradle.api.invocation.Gradle
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.tooling.core.extrasKeyOf

private val androidVariantsExtrasKey = extrasKeyOf<List<AndroidVariantReflection>>()

/**
 * Stores all collected "Android Variants" into as 'extra' to the project.
 * @see collectAndroidVariants
 *
 * `null` if the project is not an Android project
 * `empty` if no variants were collected (yet)
 *
 * Implementation Note:
 * This can be expressed using `by androidVariantsExtrasKey`:
 * Older versions of Gradle (6.0) do run with old Kotlin stdlib versions, which we
 * cannot target. Emitted code will rely on a 'void kotlin.jvm.internal.MutablePropertyReference1Impl.<init>`
 * method which is not available there.
 */
internal var Project.androidVariants: List<AndroidVariantReflection>?
    get() = extras[androidVariantsExtrasKey]
    set(value) {
        if (value != null) extras[androidVariantsExtrasKey] = value
        else extras.remove(androidVariantsExtrasKey)
    }

fun Gradle.collectAndroidVariants() {
    if (GradleVersion.current() < GradleVersion.version("8.8")) return
    lifecycle.beforeProject { project ->
        project.plugins.withId("com.android.application") {
            project.collectAndroidVariants()
        }

        project.plugins.withId("com.android.library") {
            project.collectAndroidVariants()
        }
    }
}

private fun Project.collectAndroidVariants() {
    val variants = mutableListOf<AndroidVariantReflection>()
    this.androidVariants = variants
    androidComponents?.onVariants { variants.add(it) }
}
