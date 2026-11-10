// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.stackTrace

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.lsCommand
import com.jetbrains.ls.api.features.stackTrace.LSStackTraceProvider
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class StackTracePatterns(
    val framePatterns: List<String>,
    val traceLinePatterns: List<String>,
)

@Serializable
data class ResolveStackTraceArgs(
    val lines: List<String>,
)

/**
 * The stack trace commands over the [LSStackTraceProvider]s of the configuration: the patterns of the lines a client
 * links, and the locations of each line of a trace, those of every provider together. Without providers there are
 * no patterns, so a client links nothing.
 */
object LSStackTraceCommandDescriptorProvider : LSCommandDescriptorProvider {
    const val PATTERNS_COMMAND_NAME: String = "jetbrains.stackTrace.patterns"
    const val RESOLVE_COMMAND_NAME: String = "jetbrains.stackTrace.resolve"

    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(patternsCommand, resolveCommand)

    private val patternsCommand = lsCommand<Unit, StackTracePatterns>(
        name = PATTERNS_COMMAND_NAME,
        title = LspServerBundle.message("command.stack.trace.patterns"),
    ) {
        val providers = providers()
        StackTracePatterns(providers.flatMap { it.framePatterns }.distinct(), providers.flatMap { it.traceLinePatterns }.distinct())
    }

    private val resolveCommand = lsCommand<ResolveStackTraceArgs, List<List<Location>>>(
        name = RESOLVE_COMMAND_NAME,
        title = LspServerBundle.message("command.resolve.stack.trace"),
    ) { args ->
        val providers = providers()
        contextOf<LSServer>().withAnalysisContext {
            val locationsByProvider = providers.map { it.resolve(args.lines) }
            args.lines.indices.map { index ->
                locationsByProvider.flatMap { locationsByLine -> locationsByLine.getOrElse(index) { emptyList() } }.distinct()
            }
        }
    }

    private suspend fun providers(): List<LSStackTraceProvider> =
        currentCoroutineContext()[LSConfiguration]?.entries<LSStackTraceProvider>().orEmpty()
}
