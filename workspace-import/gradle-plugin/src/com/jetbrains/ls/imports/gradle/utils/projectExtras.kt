// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils

import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.jetbrains.kotlin.tooling.core.MutableExtras
import org.jetbrains.kotlin.tooling.core.mutableExtrasOf

internal val Project.extraProperties get() = extensions.getByType(ExtraPropertiesExtension::class.java)

internal val Project.extras: MutableExtras
    @Synchronized get() {
        val key = "com.jetbrains.ls.imports.gradle.utils.extras"
        if (extraProperties.has(key)) return extraProperties[key] as MutableExtras
        val extras = mutableExtrasOf()
        extraProperties[key] = extras
        return extras
    }