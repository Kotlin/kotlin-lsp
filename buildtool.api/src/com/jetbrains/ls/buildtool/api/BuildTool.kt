// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api

import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.buildtool.api.run.RunHandle
import com.jetbrains.ls.buildtool.api.run.RunRequest
import com.jetbrains.ls.buildtool.api.run.UnsupportedRunException
import com.jetbrains.ls.buildtool.api.run.failedRunHandle
import fleet.util.async.Resource
import fleet.util.async.resourceOf
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Inputs of one [BuildTool.sync]: the [virtualFileUrlManager] the cycle commits its models with. Passed per sync
 * because it lives for one cycle, while the tool lives for the workspace.
 *
 * The caller of [BuildTool.sync] runs the sync in an analyzer context already, so a tool asks for no context of
 * its own; one that still needs a project reads `AnalyzerContext.current.project`.
 */
data class BuildToolContext(
    val virtualFileUrlManager: VirtualFileUrlManager,
)

/** Why a sync runs; the platform hands it back as [SyncRequest.toolRequest]. */
interface ImportRequest

/**
 * What one sync must cover and why it runs. Built by the platform, one per [BuildTool.sync].
 */
data class SyncRequest(
    /**
     * The sync's answer covers every file-system change at or below this watermark: a
     * platform-issued monotonic number, the last indexing batch with a file change when the cycle
     * started. Every target of one cycle receives the same value.
     */
    val targetWatermark: Long,
    /**
     * Which kind of reload this is: the initial import and an explicit client reload are forced, a
     * reload the platform asked for itself because a build script changed is not. Either way the
     * sync imports; the platform decides when a target syncs.
     */
    val force: Boolean,
    /** The request this sync serves, when it serves one. */
    val toolRequest: ImportRequest?,
)

/**
 * Could be considered as a connection to build tools daemon (mvn/gradle). Lifecycle coupled with build tool root
 */
interface BuildTool {
    /**
     * One import of the folder the driver started in, covering what [request] names. The flow
     * never throws except on cancellation; a failure arrives as [ImportEvent.Failed]. Cancelling
     * the collection cancels that import only; the tool stays alive for the next [sync].
     */
    fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent>

    /**
     * Launches [request] and keeps the program alive while the handle is in use. The tool spawns the
     * process itself. Cancellation of the handle scope kills the program; the tool stays alive.
     * A request the tool cannot run arrives as [com.jetbrains.ls.buildtool.api.run.RunTaskEvent.Failed] with an
     * [com.jetbrains.ls.buildtool.api.run.UnsupportedRunException]. The tool does not see a workspace module;
     * the caller maps the module to this tool and to a [com.jetbrains.ls.buildtool.api.run.BuildUnit].
     *
     * The default runs nothing: the tool imports only.
     */
    fun run(request: RunRequest): Resource<RunHandle> =
        resourceOf(failedRunHandle(UnsupportedRunException("This build tool does not run a program")))
}

/**
 * A [BuildTool] present in the checkout that did not import the workspace but can compile its modules: the JPS
 * modules of a checkout that also carries jps-to-bazel `BUILD` files. Started by its `CheckoutBuildDriver`.
 *
 * Asked only for modules no build tool imported (`RunTools.toolFor` answered `null`), and only by the explicit
 * Build Project / Build Module requests — never for a launch or a test run, whose classpath the JPS output paths
 * provide. A checkout tool that answered a test run would route the tests to *its* runner, which is not how the
 * workspace was imported. Several may be registered; the caller asks them in registration order and the first one
 * [presentAt] the root builds it.
 *
 * [run] takes [com.jetbrains.ls.buildtool.api.run.RunTask.Build] only. The tool sees no workspace module, so the
 * unit's `projectPath` is the module name, resolved through `BuildToolDriverContext.entityStorage`; an empty unit
 * builds every module no tool imported, under every root the tool is present at.
 */
interface CheckoutBuildTool : BuildTool {
    /**
     * Whether this tool is present in the checkout at [root]. Called under a read action, so this is a cheap file
     * check (the tool's marker files at the root), never a resolution.
     */
    fun presentAt(root: Path): Boolean
}
