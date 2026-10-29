// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
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
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<LSApplyFixCommandDescriptorProvider>()

/**
 * Provides a command that executes a [ModCommand][com.intellij.modcommand.ModCommand]-based quick fix.
 */
object LSApplyFixCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor, resolveCommandDescriptor)

    /** Set once a command without stamps was logged, so a client that never sends them does not flood the log. */
    private val unstampedCommandLogged = AtomicBoolean()

    /**
     * Executes the command of [applyFixCommand]. A command that is not a [ModCommandData.LazyAction] was computed
     * at the listing, so its stamps are checked first, and the command is refused when a document changed since.
     * The check runs in the same analysis context as the execution. That context opens only now, after the last
     * message of the client, so it sees every change the client sent before it asked to run the command.
     */
    val commandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = "Apply ModCommand",
        name = "applyModCommand",
        executor = { arguments ->
            val server = contextOf<LSServer>()
            when (val modCommandData = LSP.json.decodeFromJsonElement<ModCommandData>(arguments[0])) {
                // Performing the fix needs the analysis context of the file it belongs to, which is only known
                // after its session is found, so it is resolved before any context is opened.
                is ModCommandData.LazyAction -> executeLazyAction(modCommandData, lspClient)
                else -> {
                    val stamps = arguments.getOrNull(1)?.let { LSP.json.decodeFromJsonElement<DocumentStamps>(it) }
                    if (stamps == null && unstampedCommandLogged.compareAndSet(false, true)) {
                        // A completion remainder carries no stamps by design, and an older listing has none either.
                        LOG.info("A command without document stamps is executed unchecked: ${modCommandData::class.simpleName}")
                    }
                    server.withAnalysisContext {
                        if (stamps != null && !readAction { stamps.matchCurrent() }) {
                            LOG.info("The documents changed since the listing of ${modCommandData::class.simpleName}: $stamps")
                            refuseStaleDocument()
                        }
                        executeCommand(modCommandData, lspClient)
                    }
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

/**
 * The [Command] which asks the client to apply [modCommandData] via [LSApplyFixCommandDescriptorProvider].
 *
 * [stamps] are the [DocumentStamp]s of the files [modCommandData] was computed against, usually its
 * [stamps][ModCommandData.stamps] at the listing. The command is refused when one of them changed before it runs.
 * A [ModCommandData.LazyAction] carries no stamps, because it computes its edits at the execution.
 */
fun applyFixCommand(modCommandData: ModCommandData, stamps: DocumentStamps): Command = Command(
    title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
    command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    arguments = when (modCommandData) {
        is ModCommandData.LazyAction -> listOf(LSP.json.encodeToJsonElement<ModCommandData>(modCommandData))
        else -> listOf(
            LSP.json.encodeToJsonElement<ModCommandData>(modCommandData),
            LSP.json.encodeToJsonElement<DocumentStamps>(stamps),
        )
    },
)

/** The [Command] which asks the client to perform [lazyAction] and to apply its result. It needs no stamps. */
fun applyFixCommand(lazyAction: ModCommandData.LazyAction): Command = applyFixCommand(lazyAction, emptyMap())

/**
 * A [CodeAction] which asks the client to apply [modCommandData] via [LSApplyFixCommandDescriptorProvider].
 * See [applyFixCommand] for [stamps].
 */
fun applyFixCodeAction(
    title: String,
    kind: CodeActionKind,
    modCommandData: ModCommandData,
    stamps: DocumentStamps,
    diagnostic: Diagnostic? = null,
): CodeAction = CodeAction(
    title = title,
    kind = kind,
    diagnostics = diagnostic?.let(::listOf),
    command = applyFixCommand(modCommandData, stamps),
)
