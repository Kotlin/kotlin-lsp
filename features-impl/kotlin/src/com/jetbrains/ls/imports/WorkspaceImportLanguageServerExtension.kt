// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.features.BuildToolDriverEntry
import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.imports.gradle.GradleDriver
import com.jetbrains.ls.imports.gradle.GradleModuleMapper
import com.jetbrains.ls.imports.java.JavaCommandDriver
import com.jetbrains.ls.imports.jps.JpsDriver
import com.jetbrains.ls.imports.json.JsonDriver
import com.jetbrains.ls.imports.maven.MavenDriver
import com.jetbrains.ls.imports.maven.MavenModuleMapper

class WorkspaceImportLanguageServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(
            entries = listOf(
                LSExportWorkspaceCommandDescriptorProvider,

                BuildToolDriverEntry(JsonDriver),
                BuildToolDriverEntry(MavenDriver, moduleMapper = MavenModuleMapper),
                BuildToolDriverEntry(GradleDriver, moduleMapper = GradleModuleMapper),
                BuildToolDriverEntry(JpsDriver),
                BuildToolDriverEntry(JavaCommandDriver),
            ),
        )
}
