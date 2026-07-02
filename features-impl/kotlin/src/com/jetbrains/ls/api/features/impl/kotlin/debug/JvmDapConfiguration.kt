// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.debug

import com.jetbrains.dap.jvm.javaDebuggerModules
import com.jetbrains.dap.jvm.jvmDapPlugin
import com.jetbrains.dap.jvm.kotlinDebuggerCommonModule
import com.jetbrains.dap.jvm.kotlinDebuggerK2Module
import com.jetbrains.dap.lsp.features.completion.jvmDapLspPlugin
import com.jetbrains.dap.lsp.features.completion.kotlinDapLspPlugin
import com.jetbrains.dap.lsp.features.launch.LSResolveClassDocumentCommandDescriptorProvider
import com.jetbrains.dap.lsp.features.launch.LSResolveClasspathCommandDescriptorProvider
import com.jetbrains.dap.lsp.features.launch.LSResolveJavaExecutableCommandDescriptorProvider
import com.jetbrains.dap.lsp.features.launch.LSResolveWorkingDirectoryCommandDescriptorProvider
import com.jetbrains.ls.api.features.dap.DapPluginsProvider
import com.jetbrains.ls.api.features.impl.javaBase.LSJavaRunMainCodeLensProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

val DapJvmConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        LSResolveClassDocumentCommandDescriptorProvider,
        LSResolveClasspathCommandDescriptorProvider,
        LSResolveJavaExecutableCommandDescriptorProvider,
        LSResolveWorkingDirectoryCommandDescriptorProvider,
        LSJavaRunMainCodeLensProvider,
        LSKotlinRunMainCodeLensProvider,
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
