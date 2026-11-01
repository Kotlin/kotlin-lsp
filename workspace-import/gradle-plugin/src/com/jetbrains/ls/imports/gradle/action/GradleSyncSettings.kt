// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action

import java.io.Serializable

/**
 * @property importKotlinMultiplatform whether the sync requests the `KotlinMultiplatformModel` of each module.
 *   Without it, the sync imports a KMP project as any other Gradle project.
 */
data class GradleSyncSettings(
    val downloadLibrarySources: Boolean,
    val importKotlinMultiplatform: Boolean,
) : Serializable