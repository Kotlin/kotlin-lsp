// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.ls.api.core.launch.BuildToolLaunchContributor
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.snapshot.api.impl.core.InitConfigurationKey
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import com.jetbrains.ls.snapshot.api.impl.core.rocks.IndexingFileSystemProvider
import kotlinx.serialization.KSerializer

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}

class WorkspaceImporterEntry(
    val id: String,
    val importer: WorkspaceImporter,
    val order: String = "",
) : LSConfigurationEntry {
    init {
        // Checked once, here, because a name that cannot match any file would otherwise silently never re-import.
        importer.settingsFileNames.forEach { name ->
            require(name.isNotEmpty()) { "Importer '$id' declares an empty settings file name" }
            val unmatchable = name.filter { it in UNMATCHABLE_FILE_NAME_CHARACTERS }
            require(unmatchable.isEmpty()) {
                "Importer '$id' declares the settings file name '$name', which is compared to a file's own name " +
                "and so can never match. Remove the '$unmatchable' from it, or declare each name it stands for."
            }
        }
    }
}

/** Glob syntax and path separators: a settings file's own name contains neither. */
private const val UNMATCHABLE_FILE_NAME_CHARACTERS = "*?[]{}/\\"

/**
 * Whether [file] is one of the settings files this importer declared through
 * [WorkspaceImporter.settingsFileNames], i.e. whether a change to it must re-run the import.
 *
 * Compared by the file's own name at any depth, and rejected inside any of
 * [WorkspaceImporter.excludedDirectoryNames], which hold copies of other people's modules.
 */
fun WorkspaceImporter.isSettingsFile(file: FileUrl): Boolean =
    file.name in settingsFileNames && !file.hasAncestorNamed(excludedDirectoryNames)

private fun FileUrl.hasAncestorNamed(names: Set<String>): Boolean {
    if (names.isEmpty()) return false
    var directory = parent
    while (directory != null) {
        if (directory.name in names) return true
        directory = directory.parent
    }
    return false
}

/** Registers a build tool's build and launch support (see [BuildToolLaunchContributor]). */
class BuildToolLaunchEntry(
    val contributor: BuildToolLaunchContributor,
) : LSConfigurationEntry

fun interface WorkspaceComponentEntry : LSConfigurationEntry {
    fun component(): WorkspaceComponent<*>
}

class InitConfigurationEntry<T : Any>(
    val key: InitConfigurationKey<T>,
    val serializer: KSerializer<T>,
) : LSConfigurationEntry

/**
 * Replaces the filesystem the indexing pipeline reads from and receives change events through
 * (the local OS filesystem by default).
 *
 * [configData] carries the values decoded from the client's `initializationOptions` — register an
 * [InitConfigurationEntry] alongside this one to parameterize the provider (e.g. a mount point).
 * Return `null` when the entry is not applicable to this session (e.g. its options are absent);
 * at most one registered entry may return a provider.
 */
fun interface IndexingFileSystemProviderEntry : LSConfigurationEntry {
    fun provider(configData: LSConfigurationData): IndexingFileSystemProvider?
}

/**
 * Plugins the piece loads in its own analyzer context.
 * The analysis plugin set counts them as consumed and does not load their packaging.
 */
class ExtraPluginsProvider(
    val plugins: List<PluginMainDescriptor> = emptyList(),
) : LSConfigurationEntry

