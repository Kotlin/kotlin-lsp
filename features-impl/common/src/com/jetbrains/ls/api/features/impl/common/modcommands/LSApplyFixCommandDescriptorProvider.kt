// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.jetbrains.ls.api.core.LSServer
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Provides a command that executes a [ModCommand][com.intellij.modcommand.ModCommand]-based quick fix.
 */
object LSApplyFixCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor)

    val commandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = "Apply ModCommand",
        name = "applyModCommand",
        executor = { arguments ->
            val modCommandData = LSP.json.decodeFromJsonElement<ModCommandData>(arguments[0])
            contextOf<LSServer>().withAnalysisContext {
                executeCommand(modCommandData, lspClient)
            }
            JsonPrimitive(true)
        },
    )
}

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
    command = Command(
        title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
        command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
        arguments = listOf(
            LSP.json.encodeToJsonElement(modCommandData),
        ),
    ),
)
