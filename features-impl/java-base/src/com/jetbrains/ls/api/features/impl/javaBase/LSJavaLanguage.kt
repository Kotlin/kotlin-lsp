// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.ide.highlighter.JavaFileType
import com.jetbrains.ls.api.features.language.LSLanguage

val LSJavaLanguage: LSLanguage = LSLanguage(
    lspName = "java",
    fileType = JavaFileType.INSTANCE,
    extensions = listOf("java"),
)
