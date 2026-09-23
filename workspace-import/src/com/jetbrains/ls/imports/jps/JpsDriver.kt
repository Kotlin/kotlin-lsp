// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.jps

import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import fleet.util.async.Resource
import fleet.util.async.resource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

object JpsDriver : BuildToolDriver {
    override val type: String = "jps"

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean {
        return (projectFileOrDirectory / ".idea" / "modules.xml").exists()
    }

    /** JPS steps aside when the same root also carries the build system the `.idea` model was imported from. */
    override val isConflictAverse: Boolean get() = true

    override fun start(
        toolContext: BuildToolDriverContext,
        parameters: WorkspaceImportParameters,
    ): Resource<BuildTool> = resource { cc -> cc(JpsBuildTool(toolContext, parameters)) }
}
