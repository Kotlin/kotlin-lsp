// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.debug

import com.jetbrains.dap.platform.startDapConnection
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonPrimitive

internal object LSStartDebugCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(LSCommandDescriptor("Start debug server", "start_debug_server") {
            // TODO: use proper coroutine scope. This one is never cancelled
            @Suppress("RAW_SCOPE_CREATION")
            val port = startDapConnection(CoroutineScope(currentCoroutineContext().minusKey(Job)))
            JsonPrimitive(port)
        })
}