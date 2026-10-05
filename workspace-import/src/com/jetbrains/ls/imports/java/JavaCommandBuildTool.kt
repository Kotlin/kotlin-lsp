// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.java

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.ls.api.core.launch.JAVA_TOOL_TYPE
import com.jetbrains.ls.api.core.launch.JvmClasspath
import com.jetbrains.ls.imports.api.moduleRuntime
import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.api.run.UnsupportedRunException
import com.jetbrains.ls.api.run.failedRunHandle
import com.jetbrains.ls.api.run.freePort
import com.jetbrains.ls.api.run.jdwpAgent
import com.jetbrains.ls.api.run.toRunHandle
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.ImportRequest
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.api.putEnvironment
import fleet.util.async.Resource
import fleet.util.async.map
import fleet.util.async.resource
import fleet.util.async.resourceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import java.nio.file.Path
import kotlin.io.path.div

/**
 * The plain JVM: runs a main class of a workspace module with `java`, on the class path and module path the
 * workspace model records for the module. It imports nothing and builds nothing; a launch builds the module with
 * its own tool first, as a pre-run step.
 *
 * Not an import target: [canImportWorkspace] is `false`, and the platform starts it once per workspace.
 */
object JavaCommandDriver : BuildToolDriver {
    override val type: String = JAVA_TOOL_TYPE

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean = false

    override fun start(toolContext: BuildToolDriverContext, parameters: WorkspaceImportParameters): Resource<BuildTool> =
        resource { cc -> cc(JavaCommandBuildTool(toolContext)) }
}

/**
 * Runs [RunTask.Run] only. [com.jetbrains.ls.api.run.BuildUnit.projectPath] is the module name;
 * `sourceSet = "test"` puts the test outputs and the `TEST` scope dependencies on the class path. The paths and
 * the JDK come from the workspace model, the JPMS layout from the compiled outputs; see [javaArgs]. A
 * [JvmClasspath] extension replaces all of that with what the configuration spells; see [explicitJavaArgs].
 */
class JavaCommandBuildTool(private val toolContext: BuildToolDriverContext) : BuildTool {
    override fun sync(context: BuildToolContext, request: ImportRequest): Flow<ImportEvent> = emptyFlow()

    override val reimportRequests: Flow<ImportRequest> = emptyFlow()

    @OptIn(LowLevelLocalMachineAccess::class)
    override fun run(request: RunRequest): Resource<RunHandle> {
        val task = request.task as? RunTask.Run
            ?: return unsupported("The JVM runs a program only; a build or a test run goes through the build tool of the module")
        val classpath = request.extensions.filterIsInstance<JvmClasspath>().singleOrNull()
        request.extensions.firstOrNull { it !is JvmClasspath }?.let { return unsupported("The JVM does not support ${it::class.simpleName}") }
        val storage = toolContext.entityStorage()
        val module: ModuleEntity? = task.unit.projectPath?.takeIf { it.isNotBlank() }?.let { storage.resolve(ModuleId(it)) }
        // An additional class path joins the module's; without a module it is the whole class path.
        val explicit = classpath?.takeIf { !it.additional || module == null }
        val additional = classpath?.takeIf { it.additional && module != null }
        if (module == null && explicit == null) {
            return unsupported("A JVM run names the module to run in, or spells its class path (${task.unit.projectPath ?: "no module"})")
        }
        val runtime = module?.let { moduleRuntime(storage, it, includeTests = task.unit.sourceSet == "test") }
        // The executable rides either form of the extension: a `javaExec`-only configuration changes the JVM
        // and keeps the module's runtime.
        val java = classpath?.javaExecutable
            ?: runtime?.javaHome?.let { it / "bin" / if (OS.CURRENT == OS.Windows) "java.exe" else "java" }
            ?: return unsupported("No JDK to run with: the configuration names none, and ${module?.let { "module '${it.name}' has none" } ?: "the run names no module"}")

        val debugPort = if (request.debug) freePort() else null
        val vmArgs = request.options.vmArgs + listOfNotNull(debugPort?.let(::jdwpAgent))
        val args = if (explicit != null) explicitJavaArgs(explicit, task.entry, vmArgs, request.options.programArgs)
                   else javaArgs(runtime!!, task.entry, vmArgs, request.options.programArgs, additional)
        val process = ProcessBuilder(listOf(java.toString()) + args).apply {
            environment().putEnvironment(request.options.env)
            (request.options.workingDirectory ?: runtime?.workingDirectory)?.let { directory(Path.of(it).toFile()) }
        }
        return process.toRunHandle().map { handle ->
            object : RunHandle by handle {
                override val events: Flow<RunTaskEvent> = handle.events
                    .onStart { debugPort?.let { emit(RunTaskEvent.DebuggerReady("127.0.0.1", it)) } }
            }
        }
    }

    private fun unsupported(message: String): Resource<RunHandle> = resourceOf(failedRunHandle(UnsupportedRunException(message)))
}
