// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModUpdateSystemOptions
import com.intellij.modcommand.ModUpdateSystemOptions.ModifiedOption
import com.intellij.openapi.extensions.ExtensionPointName
import com.jetbrains.ls.api.core.LSServer

/**
 * Converts the options of a [ModUpdateSystemOptions] command into a [ModCommand] that the client can apply.
 *
 * The IDE sets these options in memory through a [com.intellij.codeInspection.options.OptionControllerProvider].
 * The server cannot do that, because the client owns the settings files. A provider changes the settings files instead.
 * Each provider handles the options of one [OptionControllerProvider][com.intellij.codeInspection.options.OptionControllerProvider]
 * prefix. An option without a provider makes the whole command unsupported.
 */
interface LSSystemOptionsCommandProvider {
    /**
     * The prefix of the options that this provider handles.
     * It is equal to [com.intellij.codeInspection.options.OptionControllerProvider.name] of the IDE provider.
     */
    val name: String

    /**
     * The command that sets the [options] in [context], or `null` when the client cannot apply it.
     *
     * @param options the options to set. Their `bindId` has no [name] prefix.
     */
    context(server: LSServer)
    fun createCommand(context: ActionContext, options: List<ModifiedOption>): ModCommand?

    companion object {
        private val EP_NAME: ExtensionPointName<LSSystemOptionsCommandProvider> = ExtensionPointName.create("ls.systemOptionsCommandProvider")

        /** The command that sets the options of [command] in [context], or `null` when a provider is absent or fails. */
        context(server: LSServer)
        fun createCommand(command: ModUpdateSystemOptions, context: ActionContext): ModCommand? {
            var result = ModCommand.nop()
            for ([name, options] in command.options.groupBy { it.bindId.substringBefore('.') }) {
                val provider = EP_NAME.extensionList.firstOrNull { it.name == name } ?: return null
                val relativeOptions = options.map { ModifiedOption(it.bindId.removePrefix("$name."), it.oldValue, it.newValue) }
                result = result.andThen(provider.createCommand(context, relativeOptions) ?: return null)
            }
            return result
        }
    }
}
