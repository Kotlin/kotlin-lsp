// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util.configuration

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val isolatedDocumentsPlugin: PluginMainDescriptor = ijPluginByXml("META-INF/language-server/kotlin-lsp/isolated-documents-mode.xml")

val IsolatedDocumentsPlugin : LSConfigurationPiece = LSConfigurationPiece(plugins = listOf(isolatedDocumentsPlugin))
