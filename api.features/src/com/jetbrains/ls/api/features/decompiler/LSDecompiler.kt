// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.decompiler

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.lsp.protocol.DocumentUri

object LSDecompiler {
    /** The URI schemes with server-provided text: an archive entry (`jar`) and a JDK module class (`jrt`). */
    val SCHEMES: List<String> = listOf("jar", "jrt")

    /** The decompiled text of [uri], or `null` when the server cannot resolve or decompile it. */
    context(server: LSServer)
    suspend fun decompile(uri: DocumentUri): DecompilerResponse? =
        server.withAnalysisContext {
            readAction {
                val psiFile = uri.findVirtualFile()?.findPsiFile(project)
                psiFile?.let { DecompilerResponse(it.text, it.language.id.lowercase()) }
            }
        }
}
