// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model.impl

import com.jetbrains.ls.imports.gradle.model.KotlinModule
import com.jetbrains.ls.imports.gradle.model.ModuleJavaSettings
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet
import java.io.File

data class ModuleSourceSetImpl(
    override val name: String,
    override val sources: MutableSet<File>,
    override val resources: MutableSet<File>,
    override val excludes: Set<String>,
    override val runtimeClasspath: Set<File>,
    override val compileClasspath: Set<File>,
    override val outputDirs: Set<File>,
    override val producedArchives: Set<File>,
    override val friendSourceSets: Set<String>,
    override val hasUnresolvedDependencies: Boolean,
    override val javaSettings: ModuleJavaSettings,
    override val kotlinModule: KotlinModule?
) : ModuleSourceSet
