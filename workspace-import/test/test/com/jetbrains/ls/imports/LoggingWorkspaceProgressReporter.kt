// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter

/**
 * Echoes import-tool (Maven/Gradle) output to the test's stdout/stderr as it arrives and accumulates it,
 * so a failed import shows the real tool error instead of an opaque "Failed to import ... project".
 */
internal class LoggingWorkspaceProgressReporter : WorkspaceImportProgressReporter {
    private val output = StringBuilder()

    val capturedOutput: String
        get() = synchronized(output) { output.toString() }

    override fun onUnresolvedDependency(depName: String) {}

    override fun onStdOutput(line: String) {
        println(line)
        synchronized(output) { output.appendLine(line) }
    }

    override fun onErrorOutput(line: String) {
        System.err.println(line)
        synchronized(output) { output.appendLine(line) }
    }

    override fun progressStatus(text: String) {
        println("[import] $text")
    }
}
