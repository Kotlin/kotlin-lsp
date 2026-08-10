// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl

import com.jetbrains.ls.imports.gradle.model.AndroidDependency
import com.jetbrains.ls.imports.gradle.model.AndroidProject

internal data class AndroidProjectImpl(
    override val buildTreePath: String,
    override val activeVariant: String?,
    override val variants: Set<String>,
    override val dependencies: Set<AndroidDependency>,
) : AndroidProject
