// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api.run

import com.intellij.openapi.util.NlsSafe

sealed interface RunTaskEvent {
    /** A message of the tool integration itself, not of the tool or the program. */
    data class SystemOutput(val line: @NlsSafe String) : RunTaskEvent
    data class StdOutput(val line: @NlsSafe String) : RunTaskEvent
    data class StdErrOutput(val line: @NlsSafe String) : RunTaskEvent

    /** The program accepts a debugger at [host]:[port]. Sent once, only for a request with `debug = true`. */
    data class DebuggerReady(val host: String, val port: Int) : RunTaskEvent

    /**
     * The unit whose runtime classpath the program runs on, when it is not the unit of the request: a Bazel
     * binary runs the main class of a library in its `deps` on the classpath of the binary. A debugger scopes
     * the session to it. Sent once, before [DebuggerReady].
     */
    data class Launched(val unit: BuildUnit) : RunTaskEvent

    /** Step [index] of a launch of several steps starts; see `List<RunStep>.run`. */
    data class StepStarted(val index: Int, val request: RunRequest) : RunTaskEvent

    data class Finished(val exitCode: Int) : RunTaskEvent
    data class Failed(val throwable: Throwable) : RunTaskEvent
}
