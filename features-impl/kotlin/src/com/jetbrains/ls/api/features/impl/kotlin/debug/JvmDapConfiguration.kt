// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.debug

import com.jetbrains.dap.jvm.javaDebuggerModules
import com.jetbrains.dap.jvm.jvmDapPlugin
import com.jetbrains.dap.jvm.kotlinDebuggerCommonModule
import com.jetbrains.dap.jvm.kotlinDebuggerK2Module
import com.jetbrains.dap.lsp.features.completion.jvmDapLspPlugin
import com.jetbrains.dap.lsp.features.completion.kotlinDapLspPlugin
import com.jetbrains.ls.api.features.dap.DapPluginsProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

val DapJvmConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        DapPluginsProvider(
            plugins = listOf(
                javaDebuggerModules,
                kotlinDebuggerCommonModule,
                kotlinDebuggerK2Module,
                jvmDapPlugin,
                jvmDapLspPlugin,
                kotlinDapLspPlugin,
            )
        )
    )
)
