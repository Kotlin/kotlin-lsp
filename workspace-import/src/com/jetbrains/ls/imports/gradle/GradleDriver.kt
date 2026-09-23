// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import fleet.util.async.Resource
import fleet.util.async.resource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

object GradleDriver : BuildToolDriver {
    override val type: String = "gradle"

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean {
        return listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        ).any { (projectFileOrDirectory / it).exists() }
    }

    override fun start(
        toolContext: BuildToolDriverContext,
        parameters: WorkspaceImportParameters,
    ): Resource<BuildTool> = resource { cc -> cc(GradleTool(toolContext, parameters)) }
}
