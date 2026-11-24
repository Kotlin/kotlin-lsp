// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.features.BuildToolDriverEntry
import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.imports.externalProcess.MavenExternalBuildDriver
import com.jetbrains.ls.imports.externalProcess.GradleExternalBuildDriver
import com.jetbrains.ls.imports.java.JavaCommandDriver
import com.jetbrains.ls.imports.jps.JpsDriver
import com.jetbrains.ls.imports.json.JsonDriver

class WorkspaceImportLanguageServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(
            entries = listOf(
                LSExportWorkspaceCommandDescriptorProvider,

                BuildToolDriverEntry(JsonDriver),
                BuildToolDriverEntry(MavenExternalBuildDriver),
                BuildToolDriverEntry(GradleExternalBuildDriver),
                BuildToolDriverEntry(JpsDriver),
                BuildToolDriverEntry(JavaCommandDriver),
            ),
        )
}
