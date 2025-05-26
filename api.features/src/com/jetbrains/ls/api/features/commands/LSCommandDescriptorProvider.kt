// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.features.LSConfigurationEntry

interface LSCommandDescriptorProvider : LSConfigurationEntry {
    val commandDescriptors: List<LSCommandDescriptor>
}