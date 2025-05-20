package com.jetbrains.ls.api.features.commands

class LSCommandDescriptor(
    val title: String,
    val name: String,
    val executor: LSCommandExecutor
)