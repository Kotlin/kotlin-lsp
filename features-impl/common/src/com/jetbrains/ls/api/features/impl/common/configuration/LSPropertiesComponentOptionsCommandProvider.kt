// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModUpdateSystemOptions.ModifiedOption
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.modcommands.LSSystemOptionsCommandProvider

/**
 * Handles the options of [PropertiesComponentOptionControllerProvider][com.intellij.codeInspection.options.PropertiesComponentOptionControllerProvider].
 * An action stores such an option to show the same value the next time, for example, a checkbox of a form.
 *
 * The server does not store these options. The command does nothing, so the rest of the action still runs.
 */
internal class LSPropertiesComponentOptionsCommandProvider : LSSystemOptionsCommandProvider {
    override val name: String get() = "PropertiesComponent"

    context(server: LSServer)
    override fun createCommand(context: ActionContext, options: List<ModifiedOption>): ModCommand {
        // TODO store the options for the client, so that the next form shows the values that the user selected
        return ModCommand.nop()
    }
}
