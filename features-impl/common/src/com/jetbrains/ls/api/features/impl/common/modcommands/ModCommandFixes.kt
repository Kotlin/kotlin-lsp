// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData

/**
 * A single fix ready to be offered to the client: the [name] to show and the [data] the client applies.
 */
data class ModCommandFix(val name: String, val data: ModCommandData)

/**
 * The fixes to offer for [this] command, which was produced for a fix presented to the user as [name].
 *
 * A [ModChooseAction] asks the UI to present a chooser of further actions. Clients declaring
 * `intellijExtensions` get it as [ModCommandData.ChooseAction] and show the menu themselves, so a single fix
 * is returned. Generic LSP clients have no such primitive
 * (see https://github.com/microsoft/language-server-protocol/issues/994), so the choice tree is expanded
 * up front into one flat fix per terminal command, each named after the path taken to reach it:
 *
 * ```
 * Action1 (ModChooseAction)          ->   Action1 → Action2
 *   ├─ Action2 (terminal)                 Action1 → Action3 → Action4
 *   └─ Action3 (ModChooseAction)          Action1 → Action3 → Action5
 *        ├─ Action4 (terminal)
 *        └─ Action5 (terminal)
 * ```
 *
 * Note that expanding performs every [ModCommandAction] in the tree on [context], and that every produced fix
 * carries the whole text of the file it changes (see [ModCommandData.UpdateFileText]). A wide tree is therefore
 * expensive both to compute and to send, so a tree yielding more than [maxFlattenedFixes] fixes is not expanded
 * at all and the fix is dropped: offering an arbitrary subset of the alternatives would silently hide the rest.
 */
context(server: LSServer)
fun ModCommand.toModCommandFixes(
    name: String,
    context: ActionContext,
    maxFlattenedFixes: Int = DEFAULT_MAX_FLATTENED_FIXES,
): List<ModCommandFix> {
    if (server.config.clientSupportsIntellijExtensions) {
        val data = ModCommandData.from(this, context, server) ?: return emptyList()
        return listOf(ModCommandFix(name, data))
    }
    return flattenChoices(context, listOf(name), maxFlattenedFixes).orEmpty()
}

/**
 * The fixes to offer for [this] action: its presentation and the command it produces, converted by
 * [toModCommandFixes]. Empty if the action is not available or fails.
 */
context(server: LSServer)
fun ModCommandAction.toModCommandFixes(
    context: ActionContext,
    maxFlattenedFixes: Int = DEFAULT_MAX_FLATTENED_FIXES,
): List<ModCommandFix> {
    // A null presentation is equivalent to getting false from IntentionAction#isAvailable, so the action is skipped.
    val presentation = runCatching {
        // If some ModCommand is not available, calling getPresentation() in such case should return null, not throw.
        // We want to know if getPresentation() throws, since it may point to missing registration of some extensions in the LSP.
        getPresentation(context)
    }.getOrHandleException {
        LOG.warn("Failed to get presentation from mod command action $this", it)
    } ?: return emptyList()

    val command = runCatching {
        perform(context)
    }.getOrHandleException {
        LOG.warn("Failed to perform mod command action $this", it)
    } ?: return emptyList()

    return command.toModCommandFixes(presentation.name, context, maxFlattenedFixes)
}

/**
 * Expands the [ModChooseAction]s in [this] into one fix per terminal command, named after [names] joined by
 * [CHOICE_SEPARATOR]. At most [maxFixes] fixes are produced for the whole tree.
 *
 * Returns `null` if any step throws or if the tree is wider than [maxFixes]: the whole fix is dropped then,
 * since a partially expanded choice tree would silently offer an incomplete set of alternatives.
 */
context(server: LSServer)
private fun ModCommand.flattenChoices(context: ActionContext, names: List<String>, maxFixes: Int): List<ModCommandFix>? {
    // Nothing fits anymore, so the tree is already known to be wider than the limit.
    if (maxFixes <= 0) return null

    if (this !is ModChooseAction) {
        val data = ModCommandData.from(this, context, server) ?: return emptyList()
        return listOf(ModCommandFix(names.joinToString(CHOICE_SEPARATOR), data))
    }

    // Checked before performing anything: since a too wide tree is dropped rather than truncated, there is no
    // point in performing its branches at all.
    if (actions.size > maxFixes) {
        LOG.debug("Not expanding the choices of '$title': ${actions.size} of them exceed the limit of $maxFixes")
        return null
    }

    val fixes = mutableListOf<ModCommandFix>()
    for (action in actions) {
        val presentation = runCatching {
            action.getPresentation(context)
        }.getOrHandleException { exception ->
            LOG.warn("Failed to get presentation from mod command action $action", exception)

            // exception happened, we bail
            return null
        } ?: continue

        val command = runCatching {
            action.perform(context)
        }.getOrHandleException { exception ->
            LOG.warn("Failed to perform mod command action $action", exception)

            // exception happened, we bail
            return null
        } ?: continue

        // something is wrong down the line, we bail
        val additionalFixes = command.flattenChoices(context, names + presentation.name, maxFixes - fixes.size) ?: return null
        fixes += additionalFixes
    }
    return fixes
}

/** Separates the choice names of a flattened [ModChooseAction], e.g. `Import class → java.util.List`. */
private const val CHOICE_SEPARATOR = " → "

/**
 * Mirrors the limit `ImportClassFixBase` puts on its own choices, the widest choice tree that is still worth
 * offering: import candidates are what makes flattening useful in the first place, while anything wider than that
 * comes from a candidate list with no limit of its own and is dropped.
 */
const val DEFAULT_MAX_FLATTENED_FIXES: Int = 25

private val LOG: Logger = logger<ModCommandFix>()
