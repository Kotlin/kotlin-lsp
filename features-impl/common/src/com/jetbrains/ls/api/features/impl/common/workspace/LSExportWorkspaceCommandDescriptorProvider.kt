// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.workspace

import com.jetbrains.ls.api.core.workspaceStructure
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.ErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.Path
import kotlin.io.path.writeText

object LSExportWorkspaceCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor("Export Workspace", "exportWorkspace") { arguments ->
            if (arguments.size != 1) {
                throwLspError(ExecuteCommand, "Expected 1 argument, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
            }
            val workspacePath = (arguments.first() as? JsonPrimitive)?.content?.let { Path(it) }
                ?: throwLspError(ExecuteCommand, "Invalid argument, expected a string, got: ${arguments[0]}", Unit, ErrorCodes.InvalidParams, null)
            val workspaceModelPath = workspacePath.resolve("workspace.json")

            withContext(Dispatchers.IO) {
                val storage = workspaceStructure.getEntityStorage()
                val json = toJson(storage, workspacePath)
                workspaceModelPath.writeText(json)
            }

            JsonPrimitive(null)
        }
    )
}