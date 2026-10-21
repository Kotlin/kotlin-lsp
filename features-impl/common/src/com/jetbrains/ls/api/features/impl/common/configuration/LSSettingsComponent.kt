// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.jetbrains.analyzer.api.AnalyzerContextKind
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.RootUriKey
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import java.nio.file.Files
import java.nio.file.Path


internal class State(
    val inspectionContext: LSInspectionContext,
    val settingsRoot: Path? = null
)

/**
 * Stores all the settings that are saved under `.idea` directory.
 */
internal object LSSettingsComponent : WorkspaceComponent<State> {
    override fun init(configData: LSConfigurationData): State {
        val root = configData[RootUriKey]?.toPath() ?: return State(LSInspectionContext.empty())

        val settingsRoot = root.resolve(Project.DIRECTORY_STORE_FOLDER)
        if (!Files.isDirectory(settingsRoot)) return State(inspectionContext = LSInspectionContext.empty())

        return State(
            inspectionContext = LSInspectionContext.new(settingsRoot),
            settingsRoot = settingsRoot,
        )
    }

    override fun projectConfigurationChanged(state: State): State {
        if (state.settingsRoot == null) return state

        return State(
            LSInspectionContext.new(state.settingsRoot),
            state.settingsRoot,
        )
    }

    override suspend fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: State,
        contextKind: AnalyzerContextKind,
    ) {
        builder.registerApplicationLSSettings(state)
    }

    override suspend fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: State,
        contextKind: AnalyzerContextKind,
    ) {
        builder.registerProjectLSSettings(state)
    }
}

context(server: LSServer)
fun AnalyzerContainerBuilder.registerApplicationLSSettings() {
    registerApplicationLSSettings(server[LSSettingsComponent])
}

private fun AnalyzerContainerBuilder.registerApplicationLSSettings(state: State) {
    serviceDoNotDispose(InspectionProfileManager::class.java, state.inspectionContext.manager)
}

context(server: LSServer)
fun AnalyzerContainerBuilder.registerProjectLSSettings() {
    registerProjectLSSettings(server[LSSettingsComponent])
}

private fun AnalyzerContainerBuilder.registerProjectLSSettings(state: State) {
    serviceDoNotDispose(InspectionProjectProfileManager::class.java, state.inspectionContext.manager)
}
