// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

fun interface LSTypedCommandBody<A, R> {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun execute(argument: A): R
}

inline fun <reified A, reified R> lsCommand(
    name: String,
    title: @LspCommand String,
    body: LSTypedCommandBody<A, R>,
): LSCommandDescriptor = LSCommandDescriptor(
    title = title,
    name = name,
    executor = { arguments ->
        val result: R = if (A::class == Unit::class) {
            body.execute(Unit as A)
        } else {
            require(arguments.size == 1) { "Command '$name' expects 1 argument, got: ${arguments.size}" }
            body.execute(LSP.json.decodeFromJsonElement<A>(arguments.first()))
        }
        LSP.json.encodeToJsonElement(result)
    },
)