// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

object LSInspectionQuickFixCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(applyQuickFixCommandDescriptor)

    val applyQuickFixCommandDescriptor: LSCommandDescriptor = LSCommandDescriptor(
        title = "Inspection Apply Fix",
        name = "inspection.applyFix",
        executor = { arguments ->
            val modCommandData = LSP.json.decodeFromJsonElement<ModCommandData>(arguments[0])
            executeCommand(modCommandData, lspClient)
            JsonPrimitive(true)
        },
    )

}