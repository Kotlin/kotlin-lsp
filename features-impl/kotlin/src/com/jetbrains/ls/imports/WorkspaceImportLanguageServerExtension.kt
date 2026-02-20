// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.WorkspaceImporterEntry
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.imports.light.LightWorkspaceImporter
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter

class WorkspaceImportLanguageServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(
            entries = listOf(
                LSExportWorkspaceCommandDescriptorProvider,

                WorkspaceImporterEntry(JsonWorkspaceImporter),
                WorkspaceImporterEntry(MavenWorkspaceImporter),
                WorkspaceImporterEntry(GradleWorkspaceImporter),
                WorkspaceImporterEntry(JpsWorkspaceImporter),
                WorkspaceImporterEntry(LightWorkspaceImporter),
            ),
        )
}
