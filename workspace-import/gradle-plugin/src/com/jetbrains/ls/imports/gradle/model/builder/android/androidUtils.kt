// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection
import org.gradle.api.Project

const val SELECTED_ANDROID_VARIANT_KEY: String = "lsp.android.variant"
const val SELECTED_ANDROID_VARIANT_ENV_KEY: String = "LSP_ANDROID_VARIANT"

/**
 * Android's buildType and flavors will form a matrix of 'variants' (e.g. debug, release, debugPaidGermany, ...)
 * Resolving sources, however, requires selecting one 'active' variant.
 *
 */
internal fun Iterable<AndroidVariantReflection>.selectActiveVariant(): AndroidVariantReflection? {
    val selected = firstOrNull { variant ->
        val selectedVariant = variant.project.providers.gradleProperty(SELECTED_ANDROID_VARIANT_KEY)
            .orElse(variant.project.providers.systemProperty(SELECTED_ANDROID_VARIANT_KEY))
            .orElse(variant.project.providers.environmentVariable(SELECTED_ANDROID_VARIANT_ENV_KEY))
            .orNull

        selectedVariant != null && variant.name == selectedVariant
    }

    return selected ?: sortedBy { if ("debug" in it.name.orEmpty()) 0 else 1 }.minByOrNull { it.name?.length ?: 0 }
}

fun Project.isAndroidProject(): Boolean {
    return pluginManager.hasPlugin("com.android.application") ||
            pluginManager.hasPlugin("com.android.library")
}
