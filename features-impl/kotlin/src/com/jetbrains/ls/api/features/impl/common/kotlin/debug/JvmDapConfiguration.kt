// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.debug

import com.jetbrains.dap.jvm.javaBackendDebuggerModule
import com.jetbrains.dap.jvm.javaDebuggerModule
import com.jetbrains.dap.jvm.javaSharedDebuggerModule
import com.jetbrains.dap.jvm.jvmDapPlugin
import com.jetbrains.ls.api.features.dap.DapConfigurationPiece

val DapJvmConfiguration: DapConfigurationPiece = DapConfigurationPiece(
    plugins = listOf(javaSharedDebuggerModule, javaDebuggerModule, javaBackendDebuggerModule, jvmDapPlugin),
)
