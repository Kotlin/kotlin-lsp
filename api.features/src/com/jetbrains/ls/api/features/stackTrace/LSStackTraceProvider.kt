// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.stackTrace

import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.protocol.Location

/**
 * Resolves the stack traces of one language to the code they point to, for the `jetbrains.stackTrace.*` commands.
 */
interface LSStackTraceProvider : LSConfigurationEntry {
    /**
     * The frames, the lines the client links to the code. The client sends [resolve] the frame together with the
     * frames and [traceLinePatterns] lines above it, and the line above them all (the exception message).
     *
     * The client runs the patterns as JavaScript regular expressions: they use only the syntax Java and JavaScript share.
     */
    val framePatterns: List<String>

    /** The other lines inside a stack trace, not linked: a cause, elided frames. */
    val traceLinePatterns: List<String>

    /**
     * @param lines the lines of a stack trace, the line clicked last
     * @return the locations of each line, empty for a line of another language or that points to no code
     */
    context(_: LSAnalysisContext)
    suspend fun resolve(lines: List<String>): List<List<Location>>
}
