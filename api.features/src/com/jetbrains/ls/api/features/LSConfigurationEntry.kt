// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceModelEntity
import com.jetbrains.rhizomedb.ChangeScope
import com.jetbrains.rhizomedb.EntityType

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}

sealed interface AnalyzerContainerType {
    data object ANALYSIS : AnalyzerContainerType
    data object WRITE : AnalyzerContainerType
    data class INDEXING(val fileSystems: AnalyzerFileSystems?) : AnalyzerContainerType
}

fun interface ApplicationInitEntry : LSConfigurationEntry {
    fun AnalyzerContainerBuilder.initApplication(application: Application, type: AnalyzerContainerType)
}

fun interface ProjectInitEntry : LSConfigurationEntry {
    fun AnalyzerContainerBuilder.initProject(project: Project, type: AnalyzerContainerType)
}

class WorkspaceImporterEntry(
    val id: String,
    val importer: WorkspaceImporter,
): LSConfigurationEntry

fun interface RhizomeEntityTypeEntry : LSConfigurationEntry {
    fun entityType(): EntityType<*>
}

fun interface RhizomeWorkspaceInitEntry : LSConfigurationEntry {
    suspend fun workspaceInit(context: AnalyzerContext): context(ChangeScope) (WorkspaceModelEntity) -> Unit
}

fun interface RhizomeLowMemoryWatcherHook: LSConfigurationEntry {
    context(_: ChangeScope)
    fun onLowMemory()
}

fun interface InvalidateHookEntry : LSConfigurationEntry {
    context(analysisContext: LSAnalysisContext)
    suspend fun invalidation(files: List<FileUrl>): context(ChangeScope) () -> Unit
}

inline fun <reified E : LSConfigurationEntry, P> LSConfiguration.analyzerContainerBuilderEntries(
    crossinline call: E.(AnalyzerContainerBuilder, P) -> Unit,
): List<AnalyzerContainerBuilder.(P) -> Unit> {
    return entries.asSequence()
        .filterIsInstance<E>()
        .map { entry ->
            val init: AnalyzerContainerBuilder.(P) -> Unit = { param ->
                entry.call(this, param)
            }
            init
        }
        .toList()
}