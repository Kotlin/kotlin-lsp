// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.kotlinLsp.requests.core.ChooseActionSession
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.ls.snapshot.api.impl.core.ChooseActionId
import com.jetbrains.ls.snapshot.api.impl.core.ChooseActionSessionComponent
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

private val LOG = logger<LSChooseActionCommandDescriptorProvider>()

/**
 * Provides the command invoked by the client after the user picks an entry in a `ModChooseAction` menu shown via
 * the `intellij/chooseAction` notification. It recovers the [ChooseActionSession] stashed in
 * [ChooseActionSessionComponent], performs the selected [ModCommandAction][com.intellij.modcommand.ModCommandAction],
 * and executes the resulting [ModCommand][com.intellij.modcommand.ModCommand]. A nested `ModChooseAction` produces a
 * fresh session and another `intellij/chooseAction` notification, driving further menu rounds.
 *
 * Arguments: `[sessionId: Long, index: Int]`.
 */
object LSChooseActionCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor)

    val commandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = "Choose ModCommand Action",
        name = "chooseModCommandAction",
        executor = { arguments ->
            require(arguments.size == 2) { "Expected 2 arguments (sessionId, index), got: ${arguments.size}" }
            val server = contextOf<LSServer>()
            val sessionId = ChooseActionId.fromJson(arguments[0])
            val index = arguments[1].jsonPrimitive.int
            when (val session = server[ChooseActionSessionComponent].get(sessionId) as ChooseActionSession?) {
                null -> {
                    lspClient.notify(
                        notificationType = ShowMessageNotificationType,
                        params = ShowMessageParams(MessageType.Error, "This action is no longer available, please try again"),
                    )
                }

                else -> {
                    // The choice was consumed; drop it so the cache does not grow. A follow-up choice (nested
                    // ModChooseAction) will be stored under a fresh id while converting the performed command.
                    server[ChooseActionSessionComponent].remove(sessionId)
                    server.withAnalysisContextAndFileSettings(session.fileUri) {
                        val data = readAction {
                            val virtualFile = session.fileUri.findVirtualFile() ?: return@readAction null
                            val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                            val action = session.actions.getOrNull(index) ?: run {
                                LOG.warn("Choice index $index is out of bounds for session with ${session.actions.size} actions")
                                return@readAction null
                            }
                            val context = ActionContext(psiFile.project, psiFile, session.offset, session.selection, null)
                            val modCommand = runCatching {
                                action.perform(context)
                            }.getOrHandleException {
                                LOG.warn("Failed to perform chosen mod command action $action", it)
                            } ?: return@readAction null
                            ModCommandData.from(modCommand, context, server)
                        }
                        if (data != null) {
                            executeCommand(data, lspClient)
                        }
                    }
                }
            }

            JsonPrimitive(true)
        },
    )
}
