// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.java.initJavaApplicationContainer
import com.jetbrains.analyzer.java.javaPlugin
import com.jetbrains.ls.api.core.util.scheme
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.WorkspaceComponentEntry
import com.jetbrains.ls.api.features.impl.common.definitions.LSCommonDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.snapshot.api.impl.core.AnalyzerContextKind
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceEvent
import com.jetbrains.lsp.protocol.URI

val LSJavaBaseLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        WorkspaceComponentEntry { JavaBaseWorkspaceComponent },
        // entries Kotlin for inlay hints to work
        // they require hover and definition requests to work on the declaration site to have some interactivity on inlays with classes from java
        LSCommonDefinitionProvider(setOf(LSJavaLanguage), setOf(TargetKind.DECLARATION)),
        LSJavaPackageDefinitionProvider(setOf(LSJavaLanguage), setOf(TargetKind.DECLARATION)),
        object : LSJavaHoverProvider() {
            override fun acceptTarget(target: PsiElement): Boolean {
                // if a user has some java support installed, then the hover results will be duplicated
                // we can and should show for libraries as Kotlin LSP vscode extension
                // handles decompiled files itself in a way only it can handle such urls via custom editors
                val containingFile = target.containingFile ?: return false
                return containingFile.virtualFile.uri.scheme in listOf(URI.Schemas.JRT, URI.Schemas.JAR, URI.Schemas.ZIP)
            }
        },
    ),
    plugins = listOf(
        javaPlugin,
        javaBaseFeature,
    ),
    languages = listOf(
        LSJavaLanguage,
    ),
)

private object JavaBaseWorkspaceComponent : WorkspaceComponent<Unit> {
    override fun init(configData: LSConfigurationData) {
    }

    override fun handleEvent(event: WorkspaceEvent, state: Unit) {
    }

    override suspend fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: Unit,
        contextKind: AnalyzerContextKind,
    ) {
        builder.initJavaApplicationContainer(application)
    }

    override suspend fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: Unit,
        contextKind: AnalyzerContextKind,
    ) {
    }
}
