// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model

import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import java.io.Serializable

interface AndroidProject : Serializable {
    val buildTreePath: String
    val activeVariant: String?
    val variants: Set<String>
    val dependencies: Set<IdeaKotlinDependency>
}
