// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.jetbrains.dap.platform.dapPlugin
import com.jetbrains.dap.platform.xDebuggerBackendModule
import com.jetbrains.dap.platform.xDebuggerImplModule
import com.jetbrains.dap.platform.xDebuggerModule
import com.jetbrains.dap.platform.xDebuggerRpcModule
import com.jetbrains.dap.platform.xDebuggerSharedModule
import com.jetbrains.ls.api.features.dap.DapPluginsProvider
import com.jetbrains.ls.api.features.impl.common.debug.LSStartDebugCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

val DapCommonConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        LSStartDebugCommandDescriptorProvider,
        DapPluginsProvider(
            plugins = listOf(
                xDebuggerModule,
                xDebuggerRpcModule,
                xDebuggerSharedModule,
                xDebuggerImplModule,
                xDebuggerBackendModule,
                dapPlugin
            ),
        )
    ),
)
