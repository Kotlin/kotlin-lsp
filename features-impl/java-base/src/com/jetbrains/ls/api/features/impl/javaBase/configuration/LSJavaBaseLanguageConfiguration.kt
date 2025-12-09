// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase.configuration

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.util.scheme
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.impl.common.definitions.LSCommonDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.javaBase.hover.LSJavaHoverProvider
import com.jetbrains.ls.api.features.impl.javaBase.language.LSJavaLanguage

import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import com.jetbrains.lsp.protocol.URI

val LSJavaBaseLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        // entries Kotlin for inlay hints to work
        // they require hover and definition requests to work on the declaration site to have some interactivity on inlays with classes from java
        LSCommonDefinitionProvider(setOf(LSJavaLanguage), setOf(TargetKind.DECLARATION)),
        object : LSJavaHoverProvider(setOf(TargetKind.DECLARATION)) {
            override fun acceptTarget(target: PsiElement): Boolean {
                // if a user has some java support installed, then the hover results will be duplicated
                // we can and should show for libraries as Kotlin LSP vscode extension
                // handles decompiled files itself in a way only it can handle such urls via custom editors
                return target.containingFile.virtualFile.uri.scheme in listOf(URI.Schemas.JRT, URI.Schemas.JAR, URI.Schemas.ZIP)
            }
        },
    ),
    plugins = listOf(
        ijPluginByXml(xmlResourcePath = "META-INF/language-server/features/javaBase/lsApiJavaBaseImpl.xml"),
    ),
    languages = listOf(
        LSJavaLanguage,
    ),
)
