// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.jetbrains.ls.api.features.impl.common.api.commonLsApiPlugin
import com.jetbrains.ls.api.features.impl.common.decompiler.LSDecompileCommandDescriptorProvider
import com.jetbrains.ls.api.features.impl.common.workspace.LSExportWorkspaceCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.lsApiPlugin

val LSCommonConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        LSDecompileCommandDescriptorProvider,
        LSExportWorkspaceCommandDescriptorProvider,
    ),
    plugins = listOf(
        lsApiPlugin,
        commonLsApiPlugin,
    ),
)