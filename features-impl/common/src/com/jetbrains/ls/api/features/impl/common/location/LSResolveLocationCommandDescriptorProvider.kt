// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.location

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Position
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path

private val LOG = logger<LSResolveLocationCommandDescriptorProvider>()

object LSResolveLocationCommandDescriptorProvider : LSCommandDescriptorProvider {
    const val COMMAND_NAME: String = "resolveLocation"

    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor(LspServerBundle.message("command.resolve.location"), COMMAND_NAME) { arguments ->
            val symbolName = arguments.decodeOptionalArgument<String>(0)
            val filePath = arguments.decodeOptionalArgument<String>(1)
            val line = arguments.decodeOptionalArgument<Int>(2)
            if (symbolName == null || filePath == null || line == null) {
                LOG.debug("resolveLocation failed to decode arguments: $arguments")
                return@LSCommandDescriptor JsonNull
            }

            val position = contextOf<LSServer>().withAnalysisContext {
                readAction {
                    resolveLocation(symbolName, filePath, line)
                }
            }
            position?.let {
                LOG.debug("resolveLocation resolved to $it")
                LSP.json.encodeToJsonElement(it)
            } ?: JsonNull.also { LOG.debug("resolveLocation no element found") }
        },
    )
}

private inline fun <reified T> List<JsonElement>.decodeOptionalArgument(index: Int): T? {
    val argument = getOrNull(index)?.takeUnless { it == JsonNull } ?: return null
    return runCatching { LSP.json.decodeFromJsonElement<T>(argument) }.getOrNull()
}

@RequiresReadLock
context(_: LSAnalysisContext)
private fun resolveLocation(symbolName: String, filePath: String, line: Int): Position? {
    if (line <= 0) return null
    val path = runCatching { Path.of(filePath) }.getOrNull() ?: return null
    val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
    val document = virtualFile.findDocument() ?: return null
    if (line > document.lineCount) return null
    val psiFile = virtualFile.findPsiFile(project) ?: return null

    val lineIndex = line - 1
    val lineEndOffset = document.getLineEndOffset(lineIndex)
    var offset = document.getLineStartOffset(lineIndex)
    while (offset <= lineEndOffset) {
        val element = PsiUtilCore.getElementAtOffset(psiFile, offset)
        val owner = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false)
        if (owner?.name == symbolName) {
            val nameIdentifier = owner.nameIdentifier ?: return null
            return document.positionByOffset(nameIdentifier.textRange.startOffset)
        }
        val nextOffset = element.textRange.endOffset
        offset = if (nextOffset > offset) nextOffset else offset + 1
    }
    return null
}
