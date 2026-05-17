// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}

class WorkspaceImporterEntry(
    val id: String,
    val importer: WorkspaceImporter,
): LSConfigurationEntry

fun interface WorkspaceComponentEntry : LSConfigurationEntry {
    fun component(): WorkspaceComponent<*>
}
