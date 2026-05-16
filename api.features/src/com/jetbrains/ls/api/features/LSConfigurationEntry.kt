// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import com.jetbrains.rhizomedb.ChangeScope
import com.jetbrains.rhizomedb.EntityType

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

fun interface RhizomeEntityTypeEntry : LSConfigurationEntry {
    fun entityType(): EntityType<*>
}

fun interface RhizomeLowMemoryWatcherHook: LSConfigurationEntry {
    context(_: ChangeScope)
    fun onLowMemory()
}
