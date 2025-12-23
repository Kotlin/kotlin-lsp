// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.debug

import com.jetbrains.dap.platform.startDapServer
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.implementation.lspScope
import kotlinx.serialization.json.JsonPrimitive

internal object LSStartDebugCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(LSCommandDescriptor("Start debug server", "start_debug_server") {
            val port = startDapServer(lspScope)
            JsonPrimitive(port)
        })
}