// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.modcommand.ModCommandWithContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionId
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionSessionComponent
import com.jetbrains.ls.snapshot.api.impl.core.LazyActionSessionInfo
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.URI

private val LOG = logger<LazyFix>()

/**
 * A fix that is offered to the client but is not performed yet.
 *
 * Performing a fix means running the code that computes its [ModCommand], which is the expensive part, and
 * which the user never asks for on most of the fixes a file produces. A [LazyFix] therefore travels to the
 * client as a [ModCommandData.LazyAction] reference into [LazyActionSessionComponent], and [executeLazyAction]
 * performs it only when the client asks to run it.
 *
 * A fix keeps the PSI of the analysis it was found in, so [perform] may find that the fix does not apply
 * anymore and return `null`.
 */
sealed class LazyFix(
    /** Where the fix was found. It also names the file the fix belongs to. */
    val context: ActionContext,
) {
    /** The name to show to the user. It is available without performing the fix. */
    abstract val name: String

    /** The command of this fix, or `null` if the fix does not apply anymore. Must run in a read action. */
    abstract fun perform(): ModCommandWithContext?

    /**
     * A fix backed by a [ModCommandAction], which covers an intention, a compiler-error fix, and an inspection
     * fix that adapts to a [ModCommandAction].
     */
    class OfAction(
        override val name: String,
        private val action: ModCommandAction,
        context: ActionContext,
    ) : LazyFix(context) {
        override fun perform(): ModCommandWithContext? {
            // A null presentation means the same as `false` from IntentionAction#isAvailable, so it is how the
            // action reports that it does not apply anymore. A fix that was available when it was offered may
            // have become unavailable since.
            runCatching {
                action.getPresentation(context)
            }.getOrHandleException {
                LOG.warn("Failed to get presentation from mod command action $action", it)
            } ?: return null

            val command = runCatching {
                action.perform(context)
            }.getOrHandleException {
                LOG.warn("Failed to perform mod command action $action", it)
            } ?: return null
            return ModCommandWithContext(context, command)
        }

        override fun toString(): String = "OfAction($action)"
    }

    /**
     * A fix backed by a [ModCommandQuickFix], which cannot adapt to a [ModCommandAction] and takes the
     * [ProblemDescriptor] of the warning it fixes instead.
     */
    class OfQuickFix(
        override val name: String,
        private val quickFix: ModCommandQuickFix,
        private val descriptor: ProblemDescriptor,
        context: ActionContext,
    ) : LazyFix(context) {
        override fun perform(): ModCommandWithContext? {
            // A ModCommandQuickFix has no presentation to ask, so the descriptor answers whether it still applies.
            if (descriptor.psiElement?.isValid != true) return null
            val command = runCatching {
                quickFix.perform(context.project, descriptor)
            }.getOrHandleException {
                LOG.warn("Failed to perform mod command quick fix $quickFix", it)
            } ?: return null
            return ModCommandWithContext(context, command)
        }

        override fun toString(): String = "OfQuickFix($quickFix)"
    }
}

/**
 * The [LazyFix] list of one [ModCommandData.LazyAction] session: the fixes of one file that one analysis found,
 * which [ModCommandData.LazyAction.index] selects from.
 */
class LazyActionSession(
    override val fileUrl: String,
    override val documentVersion: Long,
    val fileUri: URI,
    val fixes: List<LazyFix>,
) : LazyActionSessionInfo {
    override val actionCount: Int get() = fixes.size
}

/**
 * Stores [fixes] and returns the reference to each of them, in the same order.
 *
 * Must run in a read action, because it reads the version of [virtualFile].
 */
fun registerLazyFixes(server: LSServer, virtualFile: VirtualFile, fixes: List<LazyFix>): List<ModCommandData.LazyAction> {
    if (fixes.isEmpty()) return emptyList()
    val uri = virtualFile.uri
    val session = LazyActionSession(
        fileUrl = virtualFile.url,
        documentVersion = server.documents.getVersion(uri)?.toLong() ?: virtualFile.modificationStamp,
        fileUri = uri,
        fixes = fixes,
    )
    val id = server[LazyActionSessionComponent].register(session)
    return fixes.indices.map { index -> ModCommandData.LazyAction(id.id, index) }
}

/** The session and the fix [lazyAction] points to, or `null` if the session is gone. */
context(server: LSServer)
private fun resolveLazyFix(lazyAction: ModCommandData.LazyAction): Pair<LazyActionSession, LazyFix>? {
    val session = server[LazyActionSessionComponent].get(LazyActionId.of(lazyAction.sessionId)) as LazyActionSession?
    if (session == null) {
        LOG.info("The lazy action session ${lazyAction.sessionId} is gone")
        return null
    }
    val fix = session.fixes.getOrNull(lazyAction.index)
    if (fix == null) {
        LOG.warn("The index ${lazyAction.index} is out of bounds for a session with ${session.fixes.size} fixes")
        return null
    }
    return session to fix
}

/** What [resolveLazyAction] found. */
sealed interface LazyActionResult {
    /** The command of the fix, converted for the client. */
    data class Resolved(val data: ModCommandData) : LazyActionResult

    /** The session is gone, or the fix does not apply anymore. */
    data object NotAvailable : LazyActionResult

    /** The fix was performed, but its command has no LSP representation. */
    data object NotSupported : LazyActionResult

    /** The message to show to the user, or `null` if the fix resolved. */
    val errorMessage: String?
        get() = when (this) {
            is Resolved -> null
            NotAvailable -> LspServerBundle.message("error.action.not.available")
            NotSupported -> LspServerBundle.message("error.fix.not.supported")
        }
}

/**
 * Performs [this] fix and converts the command it produced. Must run in a read action, in the analysis context
 * of the file the fix belongs to.
 *
 * The fix was offered without being performed, so two things that used to be settled before the client saw the
 * fix are only known here: whether the fix still applies, and whether its command has an LSP representation.
 */
private fun LazyFix.resolve(server: LSServer): LazyActionResult {
    val performed = perform() ?: return LazyActionResult.NotAvailable
    val data = ModCommandData.from(performed.command, performed.context, server)
    if (data == null) {
        LOG.info("The command ${performed.command} of the fix '$name' has no LSP representation")
        return LazyActionResult.NotSupported
    }
    return LazyActionResult.Resolved(data)
}

/** Performs [lazyAction] and converts the command it produced, without executing that command. */
context(server: LSServer)
suspend fun resolveLazyAction(lazyAction: ModCommandData.LazyAction): LazyActionResult {
    val (session, fix) = resolveLazyFix(lazyAction) ?: return LazyActionResult.NotAvailable
    return server.withAnalysisContextAndFileSettings(session.fileUri) {
        readAction { fix.resolve(server) }
    }
}

/**
 * Performs [lazyAction] and executes the command it produced. A failure is reported to the user as a message,
 * because the user did ask for the fix to run.
 */
context(server: LSServer)
suspend fun executeLazyAction(lazyAction: ModCommandData.LazyAction, client: LspClient) {
    val resolved = resolveLazyFix(lazyAction)
    if (resolved == null) {
        client.reportLazyActionFailure(LazyActionResult.NotAvailable)
        return
    }
    val (session, fix) = resolved
    server.withAnalysisContextAndFileSettings(session.fileUri) {
        when (val result = readAction { fix.resolve(server) }) {
            is LazyActionResult.Resolved -> executeCommand(result.data, client)
            else -> client.reportLazyActionFailure(result)
        }
    }
}

private suspend fun LspClient.reportLazyActionFailure(result: LazyActionResult) {
    notify(
        notificationType = ShowMessageNotificationType,
        params = ShowMessageParams(MessageType.Error, result.errorMessage ?: return),
    )
}
