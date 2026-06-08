// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.snapshot.api.impl.core.InitConfigurationKey
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import kotlinx.serialization.KSerializer

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}

class WorkspaceImporterEntry(
    val id: String,
    val importer: WorkspaceImporter,
    val order: String = "",
) : LSConfigurationEntry

fun interface WorkspaceComponentEntry : LSConfigurationEntry {
    fun component(): WorkspaceComponent<*>
}

class InitConfigurationEntry<T : Any>(
    val key: InitConfigurationKey<T>,
    val serializer: KSerializer<T>,
) : LSConfigurationEntry
