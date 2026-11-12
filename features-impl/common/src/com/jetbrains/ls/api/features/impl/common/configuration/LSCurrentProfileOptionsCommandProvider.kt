// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.codeInspection.ex.ToolsImpl
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModUpdateSystemOptions.ModifiedOption
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.modcommands.LSSystemOptionsCommandProvider

private const val OPTIONS_PREFIX = ".options."

/**
 * Sets the inspection options of the current profile, the options of
 * [CurrentProfileOptionControllerProvider][com.intellij.codeInspection.ex.CurrentProfileOptionControllerProvider].
 * The `bindId` of an option is `<InspectionShortName>.options.<InspectionOptionId>`.
 *
 * The command changes the file of the inspection profile that the server imports, and the server reloads the profile
 * when the client saves the file. The options are set in the tool of the first scope that contains the file, or in the
 * default tool otherwise. The IDE sets them the same way.
 *
 * The command is not available when the server does not import the inspection profile.
 */
internal class LSCurrentProfileOptionsCommandProvider : LSSystemOptionsCommandProvider {
    override val name: String get() = "currentProfile"

    context(server: LSServer)
    override fun createCommand(context: ActionContext, options: List<ModifiedOption>): ModCommand? {
        if (!server.config.importInspectionProfile) return null
        val profileFile = inspectionProfileFile() ?: return null
        val project = context.project()
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val changedTools = LinkedHashMap<String, ToolsImpl>()
        for (option in options) {
            val shortName = option.bindId.substringBefore(OPTIONS_PREFIX, missingDelimiterValue = "")
            if (shortName.isEmpty()) return null
            val optionId = option.bindId.substring(shortName.length + OPTIONS_PREFIX.length)
            val tools = changedTools.getOrPut(shortName) { profile.getToolsOrNull(shortName, project)?.copy(project) ?: return null }
            tools.getInspectionTool(context.file()).tool.optionController.setOption(optionId, option.newValue)
        }
        return profileFile.writeTools(changedTools.values)
    }
}
