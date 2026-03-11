// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.jetbrains.ls.api.features.ApplicationInitEntry
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import java.util.Collections.emptyList

val LSEditorConfigLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        ApplicationInitEntry { _, _ -> initEditorConfigApplicationContainer() },
    ),
    plugins = listOf(
        editorConfigPlugin
    ),
    languages = emptyList()
)
