// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.scoped.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.kotlinLsp.requests.core.performInitialize
import com.jetbrains.ls.snapshot.api.impl.core.scoped.ClientScopedSession
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.Initialize

context(_: LSServer, _: LSConfiguration )
internal fun LspHandlersBuilder.scopedInitializeRequest() {
    request(Initialize) { initParams ->
        val rootUri = initParams.rootUri
        ClientScopedSession.update { ClientScopedSession(rootUri?.uri?.toFileUrl()) }
        performInitialize(initParams.copy(rootUri = null))
    }
}