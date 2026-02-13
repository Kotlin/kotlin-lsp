// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.language

import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.idea.KotlinLanguage

val LSKotlinLanguage: LSLanguage = LSLanguage(
    lspName = "kotlin",
    intellijLanguage = KotlinLanguage.INSTANCE,
    extensions = listOf("kt"),
)