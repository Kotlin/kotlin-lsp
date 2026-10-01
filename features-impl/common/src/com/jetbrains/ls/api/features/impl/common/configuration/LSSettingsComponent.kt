// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.api.AnalyzerContextKind
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.ls.api.core.RootUriKey
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import java.nio.file.Files


class State {
    companion object {
        val EMPTY: State = State()
    }
}

/**
 * Stores all the settings that are saved under `.idea` directory.
 */
internal object LSSettingsComponent : WorkspaceComponent<State> {
    override fun init(configData: LSConfigurationData): State {
        val root = configData[RootUriKey]?.toPath() ?: return State.EMPTY

        val ideaDirectory = root.resolve(".idea")
        if (!Files.isDirectory(ideaDirectory)) return State.EMPTY

        return State()
    }

    override suspend fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: State,
        contextKind: AnalyzerContextKind,
    ) {
        builder.service(State::class.java, state)
    }
}