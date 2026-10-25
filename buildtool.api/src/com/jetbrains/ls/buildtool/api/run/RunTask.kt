// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api.run

import com.intellij.openapi.util.NlsSafe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A unit inside the build, in the vocabulary of the tool: Gradle `:app`, a Bazel label, a Maven artifact,
 * a Go package directory. A `null` field means the value the tool derives itself. An empty unit means the
 * whole root the tool started in.
 */
data class BuildUnit(
    val projectPath: String? = null,
    /** A Gradle source set. A tool without the concept ignores it. */
    val sourceSet: String? = null,
)

/**
 * What the launched program receives. The tool applies these to the process it forks, not to its own
 * invocation: a daemon does not inherit the environment of the launcher.
 *
 * @property vmArgs arguments of the JVM that runs the program: a platform agent, `-Xmx`. A tool
 *   without a JVM ignores them.
 * @property workingDirectory the working directory of the launched program, not of the tool.
 *   `null` keeps the default of the tool.
 * @property toolArgs arguments of the tool invocation itself: `--offline`, `-Pfoo`. A tool without
 *   arguments ignores them.
 */
data class RunOptions(
    val programArgs: List<String> = emptyList(),
    val vmArgs: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val toolArgs: List<String> = emptyList(),
)

/** What to do with a [BuildUnit]. The tool maps every string to its own terms. */
sealed interface RunTask {
    val unit: BuildUnit

    /** Compiles [unit]; an empty unit compiles the whole root. The tool decides whether to clean first. */
    data class Build(override val unit: BuildUnit = BuildUnit(), val testScope: Boolean = false) : RunTask

    /**
     * Launches [entry]: a main class, a Go package, a Python module or script. A JPMS main class is spelled
     * as `java -m` takes it: `module/com.acme.Main`; a JVM tool then runs it on the module path.
     */
    data class Run(override val unit: BuildUnit, val entry: String) : RunTask

    /** Runs [tests]: opaque ids such as a JUnit FQN, a pytest nodeid or a `go test -run` pattern. */
    data class Test(override val unit: BuildUnit, val tests: List<String>) : RunTask
}

/**
 * An addition to a launch: a profiler, coverage, ... A marker like `ImportRequest`: the platform does
 * not read it. The tool applies the extensions it knows and fails with [UnsupportedRunException] on
 * the rest.
 */
interface RunExtension

/**
 * One request to [com.jetbrains.ls.buildtool.api.BuildTool.run].
 *
 * @property debug whether the launched program must accept a debugger. The tool knows its own
 *   debugger: `-agentlib:jdwp`, `dlv`, `debugpy`.
 */
data class RunRequest(
    val task: RunTask,
    val options: RunOptions = RunOptions(),
    val debug: Boolean = false,
    val extensions: List<RunExtension> = emptyList(),
)

/**
 * A running program, handed over by a [Resource][fleet.util.async.Resource]: the end of the `use` body is a soft
 * stop, and a cancellation of the scope kills the program.
 *
 * A soft stop asks the program to end (SIGTERM off Windows), so a JVM runs its shutdown hooks; the release phase
 * keeps reading the output until [RunTaskEvent.Finished]. A program that ignores the request is killed after a
 * grace timeout.
 */
interface RunHandle {
    /**
     * Output and the end of the program as a hot stream: exactly one consumer reads it, and that consumer lives
     * outside the `use` body, so it still reads the output a soft stop produces. The last event is
     * [RunTaskEvent.Finished], or [RunTaskEvent.Failed] when nothing started. Never throws except on cancellation.
     */
    val events: Flow<RunTaskEvent>

    /** Program input. Closing it closes the stdin of the program. */
    val input: SendChannel<String>
}

/** The tool cannot run the request; [message] is user-visible. */
class UnsupportedRunException(message: @NlsSafe String) : Exception(message)

/** A handle that failed before a program started: one [RunTaskEvent.Failed], and an input nobody reads. */
fun failedRunHandle(cause: Throwable): RunHandle = object : RunHandle {
    override val events: Flow<RunTaskEvent> = flowOf(RunTaskEvent.Failed(cause))
    override val input: SendChannel<String> = Channel<String>().apply { close() }
}
