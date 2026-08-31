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
 * One terminal command of an expanded choice tree, together with the [choiceNames] of the choices taken to
 * reach it, outermost first. Empty for a command that held no choice at all.
 */
data class FlattenedModCommand(val choiceNames: List<String>, val command: ModCommand)

/**
 * The fixes to offer for [this] fix, which is not performed yet.
 *
 * A client declaring `intellijExtensions` gets one fix that carries a [ModCommandData.LazyAction] reference, so
 * the fix is performed only if the user asks for it. Because nothing is performed here, two things are only
 * known once the client runs the fix: whether the fix still applies, and whether its command has an LSP
 * representation. [executeLazyAction] reports both to the user as a message.
 *
 * A generic LSP client cannot handle a [ModChooseAction] (see
 * https://github.com/microsoft/language-server-protocol/issues/994), and the choice tree can only be expanded
 * by performing it, so for such a client the fix is performed here and [ModCommand.toModCommandFixes] flattens
 * what it produced.
 */
context(server: LSServer)
fun LazyFix.toModCommandFixes(maxFlattenedFixes: Int = DEFAULT_MAX_FLATTENED_FIXES): List<ModCommandFix> {
    if (server.config.clientSupportsIntellijExtensions) {
        // A fix is stored per file, so one of a file that has no virtual file cannot be offered lazily.
        val virtualFile = context.file.virtualFile
        if (virtualFile == null) {
            LOG.debug("Not offering the fix '$name': ${context.file} has no virtual file")
            return emptyList()
        }
        val action = registerLazyFixes(server, virtualFile, listOf(this)).single()
        return listOf(ModCommandFix(name, action))
    }
    val performed = perform() ?: return emptyList()
    return performed.command.toModCommandFixes(name, performed.context, maxFlattenedFixes)
}

/**
 * The fixes to offer for [this] command, which was produced for a fix presented to the user as [name].
 *
 * This is the eager path, used for a client that does not declare `intellijExtensions` and for a command that
 * was performed for another reason. The choice tree of a [ModChooseAction] is expanded up front by
 * [flattenChoices] into one flat fix per terminal command, each named after the path taken to reach it, and a
 * tree wider than [maxFlattenedFixes] is dropped entirely.
 */
context(server: LSServer)
private fun ModCommand.toModCommandFixes(
    name: String,
    context: ActionContext,
    maxFlattenedFixes: Int = DEFAULT_MAX_FLATTENED_FIXES,
): List<ModCommandFix> {
    if (server.config.clientSupportsIntellijExtensions) {
        val data = ModCommandData.from(this, context, server) ?: return emptyList()
        return listOf(ModCommandFix(name, data))
    }
    val flattened = flattenChoices(context, maxFlattenedFixes) ?: return emptyList()
    return flattened.mapNotNull { (choiceNames, command) ->
        val data = ModCommandData.from(command, context, server) ?: return@mapNotNull null
        ModCommandFix((listOf(name) + choiceNames).joinToString(CHOICE_SEPARATOR), data)
    }
}

/**
 * The fixes to offer for [this] action: its presentation and the command it produces, converted by
 * [toModCommandFixes]. Empty if the action is not available.
 *
 * @param name the name to show, or `null` to take it from the presentation of the action
 */
context(server: LSServer)
fun ModCommandAction.toModCommandFixes(
    context: ActionContext,
    maxFlattenedFixes: Int = DEFAULT_MAX_FLATTENED_FIXES,
    name: String? = null,
): List<ModCommandFix> {
    val lazyFix = toLazyFix(context, name) ?: return emptyList()
    return lazyFix.toModCommandFixes(maxFlattenedFixes)
}

/**
 * @param name the name to show, or `null` to take it from the presentation of the action
 */
fun ModCommandAction.toLazyFix(context: ActionContext, name: String? = null): LazyFix? {
    // A null presentation is equivalent to getting false from IntentionAction#isAvailable, so the action is skipped.
    val presentation = runCatching {
        // If some ModCommand is not available, calling getPresentation() in such case should return null, not throw.
        // We want to know if getPresentation() throws, since it may point to missing registration of some extensions in the LSP.
        getPresentation(context)
    }.getOrHandleException {
        LOG.warn("Failed to get presentation from mod command action $this", it)
    } ?: return null

    return LazyFix.OfAction(name ?: presentation.name, this, context)
}

/**
 * Expands the [ModChooseAction]s in [this] into one [FlattenedModCommand] per terminal command:
 *
 * ```
 * Action1 (ModChooseAction)          ->   [Action2]
 *   ├─ Action2 (terminal)                 [Action3, Action4]
 *   └─ Action3 (ModChooseAction)          [Action3, Action5]
 *        ├─ Action4 (terminal)
 *        └─ Action5 (terminal)
 * ```
 *
 * Note that this performs every [ModCommandAction] in the tree on [context], which is why a tree wider than
 * [maxCommands] is not expanded at all: there is no point in performing branches that will not be offered.
 *
 * Returns `null` if any step throws or if the tree is wider than [maxCommands]. Both cases are for the caller
 * to drop the whole thing, since a partially expanded choice tree would silently hide some of the alternatives.
 */
fun ModCommand.flattenChoices(context: ActionContext, maxCommands: Int): List<FlattenedModCommand>? =
    flattenChoices(context, emptyList(), maxCommands)

private fun ModCommand.flattenChoices(
    context: ActionContext,
    choiceNames: List<String>,
    maxCommands: Int,
): List<FlattenedModCommand>? {
    // Nothing fits anymore, so the tree is already known to be wider than the limit.
    if (maxCommands <= 0) return null

    if (this !is ModChooseAction) {
        return listOf(FlattenedModCommand(choiceNames, this))
    }

    // Checked before performing anything: since a too wide tree is dropped rather than truncated, there is no
    // point in performing its branches at all.
    if (actions.size > maxCommands) {
        LOG.debug("Not expanding the choices of '$title': ${actions.size} of them exceed the limit of $maxCommands")
        return null
    }

    val commands = mutableListOf<FlattenedModCommand>()
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
        val additionalCommands =
            command.flattenChoices(context, choiceNames + presentation.name, maxCommands - commands.size) ?: return null
        commands += additionalCommands
    }
    return commands
}

/** Separates the choice names of a flattened [ModChooseAction], e.g. `Import class → java.util.List`. */
const val CHOICE_SEPARATOR: String = " → "

/**
 * Mirrors the limit `ImportClassFixBase` puts on its own choices, the widest choice tree that is still worth
 * offering: import candidates are what makes flattening useful in the first place, while anything wider than that
 * comes from a candidate list with no limit of its own and is dropped.
 */
const val DEFAULT_MAX_FLATTENED_FIXES: Int = 10

private val LOG: Logger = logger<ModCommandFix>()
