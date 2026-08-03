// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder.android

import com.jetbrains.ls.imports.gradle.utils.AndroidVariantReflection

/**
 * Wraps the collected variants so that the extras key for them can be created from a non-generic type.
 *
 * Implementation Note:
 * `extrasKeyOf<T>()` inlines `typeOf<T>()`, which for a generic `T` emits a `KTypeProjection.invariant` call
 * per type argument. Since Kotlin 2.4.20 that resolves to the static accessor on `KTypeProjection` rather than
 * the one on its companion, and the Kotlin 1.3.50 stdlib bundled with Gradle 6 only has the companion one, so
 * `extrasKeyOf<List<AndroidVariantReflection>>()` made applying this plugin fail on Gradle 6 with a
 * `NoSuchMethodError`. `typeOf` of a non-generic type emits no projection at all.
 *
 * The list is stored by reference, so a caller may keep filling it after the holder has been created.
 */
internal class AndroidVariantsHolder(val variants: List<AndroidVariantReflection>)
