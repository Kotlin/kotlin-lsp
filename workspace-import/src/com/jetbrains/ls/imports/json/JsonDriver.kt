// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import fleet.util.async.Resource
import fleet.util.async.resource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

object JsonDriver : BuildToolDriver {
    override val type: String = "json"

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean =
        (projectFileOrDirectory / "workspace.json").exists()

    /** A `workspace.json` names the build system that produced it, so it steps aside for that build system. */
    override val isConflictAverse: Boolean get() = true

    override fun start(
        toolContext: BuildToolDriverContext,
        parameters: WorkspaceImportParameters,
    ): Resource<BuildTool> = resource { cc -> cc(JsonTool(toolContext, parameters)) }
}
