// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.impl

import com.jetbrains.ls.imports.gradle.model.ModuleJavaSettings
import java.io.File

data class ModuleJavaSettingsImpl(
    override val compileOptions: Set<String>,
    override val toolchainVersion: Int?,
    override val toolchainJavaHome: File?,
    override val toolchainJavaVersion: Int?,
    override val sourceCompatibility: String?,
    override val targetCompatibility: String?
) : ModuleJavaSettings
