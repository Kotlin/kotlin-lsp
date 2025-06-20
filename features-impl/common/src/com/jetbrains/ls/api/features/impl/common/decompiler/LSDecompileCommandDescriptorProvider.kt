// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.decompiler

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.decompiler.DecompilerResponse
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object LSDecompileCommandDescriptorProvider : LSCommandDescriptorProvider {
    private val ALLOWED_SCHEMES = setOf("jar", "jrt")

    override val commandDescriptors: List<LSCommandDescriptor> =
        listOf(LSCommandDescriptor("Decompile", "decompile",) { arguments ->
                if (arguments.size != 1) {
                    throwLspError(ExecuteCommand, "Expected 1 argument, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
                }
                val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments.first())
                val scheme = documentUri.uri.scheme
                if (scheme !in ALLOWED_SCHEMES) {
                    throwLspError(ExecuteCommand, "Unexpected URI scheme to decompile: $scheme", Unit, ErrorCodes.InvalidParams, null)
                }
                val response: DecompilerResponse? = withAnalysisContext {
                    runReadAction {
                        val psiFile = documentUri.findVirtualFile()?.findPsiFile(project)
                        psiFile?.let { DecompilerResponse(it.text, it.language.id.lowercase()) }
                    }
                }

                response?.let{LSP.json.encodeToJsonElement(it)} ?: JsonPrimitive(null as String?)
            }
        )
}