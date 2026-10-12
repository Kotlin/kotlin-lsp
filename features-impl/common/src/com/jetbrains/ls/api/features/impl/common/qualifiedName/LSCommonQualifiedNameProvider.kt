// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.qualifiedName

import com.intellij.ide.actions.FqnUtil
import com.intellij.ide.actions.QualifiedNameProviderUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.TargetKind
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getTargetsAtPosition
import com.jetbrains.ls.api.features.qualifiedName.LSQualifiedNameProvider
import com.jetbrains.ls.api.features.qualifiedName.QualifiedNameParams
import com.jetbrains.lsp.implementation.LspHandlerContext

/**
 * Serves every language through the `qualifiedNameProvider` extensions the server ships: the declaration or the
 * resolved reference at the position, adjusted as Copy Reference does it.
 */
object LSCommonQualifiedNameProvider : LSQualifiedNameProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getQualifiedName(params: QualifiedNameParams): String? {
        return server.withAnalysisContext {
            readAction {
                val psiFile = params.textDocument.findVirtualFile()?.findPsiFile(project) ?: return@readAction null
                psiFile.getTargetsAtPosition(params.position, TargetKind.ALL).firstNotNullOfOrNull { target ->
                    FqnUtil.getQualifiedNameFromProviders(QualifiedNameProviderUtil.adjustElementToCopy(target) ?: target)
                }
            }
        }
    }
}
