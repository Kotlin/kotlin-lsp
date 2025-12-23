// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.jetbrains.dap.platform.*
import com.jetbrains.ls.api.features.dap.DapConfigurationPiece
import com.jetbrains.ls.api.features.impl.common.debug.LSStartDebugCommandDescriptorProvider

val DapCommonConfiguration: DapConfigurationPiece = DapConfigurationPiece(
    commands = listOf(LSStartDebugCommandDescriptorProvider),
    plugins = listOf(xDebuggerModule, xDebuggerRpcModule, xDebuggerSharedModule, xDebuggerImplModule, xDebuggerBackendModule, dapPlugin),
)
