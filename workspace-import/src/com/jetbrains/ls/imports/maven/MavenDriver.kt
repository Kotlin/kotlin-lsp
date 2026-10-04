// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.importRoot
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import fleet.util.async.Resource
import fleet.util.async.resource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

object MavenDriver : BuildToolDriver {
    override val type: String = "maven"
    override val externalSystemId: String = "MAVEN"

    /** The module directory relative to the reactor root, as `-pl` takes it; the root module is the whole reactor. */
    override fun unitOf(module: ModuleEntity): BuildUnit {
        val root = module.importRoot ?: return BuildUnit()
        val dir = module.exModuleOptions?.linkedProjectPath?.takeUnless { it.isBlank() }?.let(Path::of) ?: return BuildUnit()
        return BuildUnit(projectPath = root.relativize(dir).toString().ifEmpty { null })
    }

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean {
        // A file is importable when its name is a recognizable pom spelling (`mvn -f dev_pom.xml`-style
        // non-standard names included); a directory when it holds the conventional pom.
        return if (projectFileOrDirectory.isRegularFile()) isPomFileName(projectFileOrDirectory.name)
               else (projectFileOrDirectory / "pom.xml").exists()
    }

    private fun isPomFileName(name: String): Boolean =
        name.endsWith("pom.xml") || name.startsWith("pom.") || name.endsWith(".pom")

    override fun start(
        toolContext: BuildToolDriverContext,
        parameters: WorkspaceImportParameters,
    ): Resource<BuildTool> = resource { cc -> cc(MavenTool(toolContext, parameters)) }
}
