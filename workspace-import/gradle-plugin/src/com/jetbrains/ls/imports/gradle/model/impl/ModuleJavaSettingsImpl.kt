// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl

import com.jetbrains.ls.imports.gradle.model.ModuleJavaSettings

data class ModuleJavaSettingsImpl(
    override val compileOptions: Set<String>,
    override val toolchainVersion: Int?,
    override val sourceCompatibility: String?,
    override val targetCompatibility: String?
) : ModuleJavaSettings