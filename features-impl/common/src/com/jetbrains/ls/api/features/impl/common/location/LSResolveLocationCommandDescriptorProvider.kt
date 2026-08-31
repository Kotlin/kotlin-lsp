// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.location

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.commands.LSResolveLocationCommand
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider

object LSResolveLocationCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor(LspServerBundle.message("command.resolve.location"), LSResolveLocationCommand.COMMAND_NAME) { arguments ->
            contextOf<LSServer>().withAnalysisContext {
                LSResolveLocationCommand().execute(project, arguments)
            }
        },
    )
}
