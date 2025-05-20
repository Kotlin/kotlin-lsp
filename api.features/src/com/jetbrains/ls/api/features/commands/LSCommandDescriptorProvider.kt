package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.features.LSConfigurationEntry

interface LSCommandDescriptorProvider : LSConfigurationEntry {
    val commandDescriptors: List<LSCommandDescriptor>
}