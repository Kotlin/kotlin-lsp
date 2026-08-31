// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Provides a command that executes a [ModCommand][com.intellij.modcommand.ModCommand]-based quick fix.
 */
object LSApplyFixCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor, resolveCommandDescriptor)

    val commandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = "Apply ModCommand",
        name = "applyModCommand",
        executor = { arguments ->
            val server = contextOf<LSServer>()
            when (val modCommandData = LSP.json.decodeFromJsonElement<ModCommandData>(arguments[0])) {
                // Performing the fix needs the analysis context of the file it belongs to, which is only known
                // after its session is found, so it is resolved before any context is opened.
                is ModCommandData.LazyAction -> executeLazyAction(modCommandData, lspClient)
                else -> server.withAnalysisContext {
                    executeCommand(modCommandData, lspClient)
                }
            }
            JsonPrimitive(true)
        },
    )

    /**
     * Performs a [ModCommandData.LazyAction] and returns the [ModCommandData] of the command it produced,
     * without executing that command. It answers what a fix would do, which the test harness compares with its
     * expected LSP responses, and which a client may use to preview a fix.
     *
     * Returns `null` when the fix does not apply anymore or its command has no LSP representation.
     */
    val resolveCommandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = LspServerBundle.message("command.resolve.mod.command"),
        name = "resolveModCommand",
        executor = { arguments ->
            val lazyAction = LSP.json.decodeFromJsonElement<ModCommandData.LazyAction>(arguments[0])
            when (val result = resolveLazyAction(lazyAction)) {
                is LazyActionResult.Resolved -> LSP.json.encodeToJsonElement<ModCommandData>(result.data)
                else -> JsonNull
            }
        },
    )
}

/** The [Command] which asks the client to apply [modCommandData] via [LSApplyFixCommandDescriptorProvider]. */
fun applyFixCommand(modCommandData: ModCommandData): Command = Command(
    title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
    command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    arguments = listOf(
        LSP.json.encodeToJsonElement(modCommandData),
    ),
)

/**
 * A [CodeAction] which asks the client to apply [modCommandData] via [LSApplyFixCommandDescriptorProvider].
 */
fun applyFixCodeAction(
    title: String,
    kind: CodeActionKind,
    modCommandData: ModCommandData,
    diagnostic: Diagnostic? = null,
): CodeAction = CodeAction(
    title = title,
    kind = kind,
    diagnostics = diagnostic?.let(::listOf),
    command = applyFixCommand(modCommandData),
)
