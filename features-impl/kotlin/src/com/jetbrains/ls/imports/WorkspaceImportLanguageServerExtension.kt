// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.features.BuildToolDriverEntry
import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.imports.api.DelegatedBuildToolDriver
import com.jetbrains.ls.imports.gradle.GradleDriver
import com.jetbrains.ls.imports.jps.JpsDriver
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.imports.maven.MavenDriver

class WorkspaceImportLanguageServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(
            entries = listOf(
                LSExportWorkspaceCommandDescriptorProvider,

                BuildToolDriverEntry(DelegatedBuildToolDriver.of(JsonWorkspaceImporter, "json")),
                BuildToolDriverEntry(MavenDriver),
                BuildToolDriverEntry(GradleDriver),
                BuildToolDriverEntry(JpsDriver),
            ),
        )
}
