// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.codeInspection.ex.ProfileFile
import com.intellij.openapi.application.Application
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.jetbrains.analyzer.api.AnalyzerContextKind
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.analyzer.codeServer.ScopeStorage
import com.jetbrains.analyzer.filesystem.toList
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.RootUriKey
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.SessionComponent
import com.jetbrains.ls.snapshot.api.impl.core.SessionEvent
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import java.nio.file.Path


internal class State(val settingsRoot: Path?) {
    val readSettings: Settings = Settings(settingsRoot)
    val writeSettings: Settings = Settings(settingsRoot)

    fun settings(contextKind: AnalyzerContextKind): Settings = when (contextKind) {
        AnalyzerContextKind.Write -> writeSettings
        AnalyzerContextKind.Analysis, AnalyzerContextKind.Invalidation, is AnalyzerContextKind.Indexing -> readSettings
    }
}

internal class Settings(settingsRoot: Path?) {
    val inspectionContext: LSInspectionContext = settingsRoot?.let { LSInspectionContext.new(it) } ?: LSInspectionContext.empty()
    val scopeContext: LSScopeContext = settingsRoot?.let { LSScopeContext.new(it) } ?: LSScopeContext.empty()
}

/**
 * Stores the settings under the `.idea` directory for one session snapshot.
 */
internal object LSSettingsComponent : SessionComponent<State> {
    override fun init(configData: LSConfigurationData): State {
        // The settings directory is kept even when it is absent: a later change can create it, and the settings then reload.
        return State(configData[RootUriKey]?.toPath()?.resolve(Project.DIRECTORY_STORE_FOLDER))
    }

    override fun handleEvent(event: SessionEvent, state: State): State = when (event) {
        is SessionEvent.InvalidateFiles -> {
            val root = state.settingsRoot?.toFileUrl()
            val directories = root?.let { listOf(it.child("inspectionProfiles"), it.child("scopes")) }.orEmpty()
            if (event.files.toList().any { file -> directories.any { it.contains(file) || file.contains(it) } }) {
                State(state.settingsRoot)
            } else {
                state
            }
        }
        SessionEvent.LowMemory -> State(state.settingsRoot)
    }

    override suspend fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: State,
        contextKind: AnalyzerContextKind,
    ) {
        builder.registerApplicationLSSettings(state.settings(contextKind))
    }

    override suspend fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: State,
        contextKind: AnalyzerContextKind,
    ) {
        builder.registerProjectLSSettings(state.settings(contextKind).inspectionContext)
    }
}

context(server: LSServer)
fun AnalyzerContainerBuilder.registerApplicationLSSettings() {
    registerApplicationLSSettings(server[LSSettingsComponent].settings(AnalyzerContext.current.contextKind))
}

private fun AnalyzerContainerBuilder.registerApplicationLSSettings(settings: Settings) {
    serviceDoNotDispose(LSInspectionContext::class.java, settings.inspectionContext)
    serviceDoNotDispose(InspectionProfileManager::class.java, settings.inspectionContext.manager)
    serviceDoNotDispose(ScopeStorage::class.java, settings.scopeContext.storage)
}

@Suppress("IncorrectServiceRetrieving")
fun AnalyzerContainerBuilder.registerProjectLSSettings() {
    registerProjectLSSettings(service<LSInspectionContext>())
}

private fun AnalyzerContainerBuilder.registerProjectLSSettings(context: LSInspectionContext) {
    serviceDoNotDispose(InspectionProjectProfileManager::class.java, context.manager)
}

/**
 * The file of the inspection profile the server reads, or the file to create for it. It is `null` when the project
 * settings select a profile which the server does not read.
 */
@Suppress("IncorrectServiceRetrieving")
fun inspectionProfileFile(): ProfileFile? = service<LSInspectionContext>().profileFile
