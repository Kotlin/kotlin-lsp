// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger

/**
 * Represents a chain of [ModCommandAction]s produced by [ModChooseAction]s.
 * In a trivial case consists of a single non-choice action.
 *
 * [steps] represents a chain of choices which resulted in [leaf].
 *
 * The overall order of steps looks like this:
 * - `step.first()`
 * - ...
 * - `step.last()`
 * - `leaf`
 *
 * [steps] can be empty, in that case [leaf] simply represents a single non-choice action.
 *
 * To unfold a [ModCommandAction] into a list of [ModCommandAction], see [flattenChoiceActions].
 *
 * ## Notes
 *
 * Q: Why this class and [flattenChoiceActions] are even needed? Can't we simply add a new
 * [com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData] subtype?
 *
 * A: We have to resort to expanding [ModChooseAction] into flattened [ModChooseActionChain] because
 * it's currently impossible to natively express [ModChooseAction] with [com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData]
 * and current version of LSP protocol. We can probably get back to this question in the future,
 * when https://github.com/microsoft/language-server-protocol/issues/994 or similar capabilities
 * are provided.
 */
data class ModChooseActionChain(
    val steps: List<Step>,
    val leaf: Leaf,
) {
    data class Step(
        val action: ModCommandAction,
        val presentation: Presentation,
        val command: ModChooseAction,
    )

    data class Leaf(
        val action: ModCommandAction,
        val presentation: Presentation,
        val command: ModCommand,
    )
}

/**
 * Builds a combined title from [ModChooseActionChain.steps] in the chain plus the [ModChooseActionChain.leaf],
 * with an arrow separator (→) between individual titles.
 *
 * Uses corresponding [Presentation.name] as titles.
 *
 * For example, if the chain has a step with title "Import" and the leaf has title "java.util.List",
 * the result would be "Import → java.util.List".
 */
fun ModChooseActionChain.combinedPresentationNames(): String {
    if (steps.isEmpty()) return leaf.presentation.name

    val allTitles = steps.map { it.presentation.name } + leaf.presentation.name
    return allTitles.joinToString(separator = " → ")
}

/**
 * Takes a [ModCommandAction] and recursively expands all [ModChooseAction] nodes into a list of [ModChooseActionChain]s.
 *
 * Each resulting [ModChooseActionChain] represents one possible path from this action to a terminal (non-choice) command.
 * If this action is not a [ModChooseAction], returns a list with a single [ModChooseActionChain] with empty [ModChooseActionChain.steps].
 *
 * Example:
 * ```
 * Action1 (ModChooseAction)
 *   ├─ Action2 (terminal)
 *   └─ Action3 (ModChooseAction)
 *        ├─ Action4 (terminal)
 *        └─ Action5 (terminal)
 * ```
 * Would produce 3 chains:
 * ```
 * - [Action1] -> Action2
 * - [Action1, Action3] -> Action4
 * - [Action1, Action3] -> Action5
 * ```
 *
 * Note 1: To analyze [ModCommand]s, all of the [ModCommandAction]s in the chains will be performed in the [context].
 *
 * Note 2: Only top-level [ModChooseAction] commands are expanded. If a [ModChooseAction] is wrapped inside another
 * [ModCommand] (e.g., as part of a composite command), it will not be recursively expanded and will be treated
 * as a terminal command.
 *
 * Note 3: If at any point of the execution there is an exception coming from the [ModCommandAction] being performed,
 * the exception is logged and an empty list is returned.
 */
fun ModCommandAction.flattenChoiceActions(context: ActionContext): List<ModChooseActionChain> {
    return flattenChoiceActionsImpl(context, steps = emptyList()).orEmpty()
}

/**
 * Returns a resulting flattened list of [ModChooseActionChain]s, or `null` if there is an exception in any step of the way.
 */
private fun ModCommandAction.flattenChoiceActionsImpl(
    context: ActionContext,
    steps: List<ModChooseActionChain.Step>
): List<ModChooseActionChain>? {
    val modCommandAction = this

    val presentation =
        runCatching {
            modCommandAction.getPresentation(context)
        }.getOrHandleException { exception ->
            LOG.warn("Failed to get presentation from mod command action $modCommandAction", exception)

            // exception happened, we bail
            return null
        }

    if (presentation == null) return emptyList()

    val command =
        runCatching {
            modCommandAction.perform(context)
        }.getOrHandleException { exception ->
            LOG.warn("Failed to perform mod command action $modCommandAction", exception)

            // exception happened, we bail
            return null
        }

    if (command == null) return emptyList()

    return when (command) {
        is ModChooseAction -> {
            val newChain = steps + ModChooseActionChain.Step(modCommandAction, presentation, command)
            command.actions.flatMap { subAction ->
                subAction.flattenChoiceActionsImpl(context, newChain)
                    // something is wrong down the line, we bail
                    ?: return null
            }
        }
        else -> listOf(ModChooseActionChain(steps, ModChooseActionChain.Leaf(modCommandAction, presentation, command)))
    }
}

private val LOG: Logger = logger<ModChooseActionChain>()