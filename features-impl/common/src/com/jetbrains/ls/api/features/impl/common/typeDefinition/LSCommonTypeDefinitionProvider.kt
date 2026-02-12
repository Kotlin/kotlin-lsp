// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.typeDefinition

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.typeDefinition.LSTypeDefinitionProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.TypeDefinitionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonTypeDefinitionProvider(
    override val supportedLanguages: Set<LSLanguage>
) : LSTypeDefinitionProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideTypeDefinitions(params: TypeDefinitionParams): Flow<Location> = flow {
        server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val offset = document.offsetByPosition(params.position)

                val editor = ImaginaryEditor(project, document).apply {
                    caretModel.primaryCaret.moveToOffset(offset)
                }

                // Reuse IntelliJ's existing type declaration logic
                val flags = TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.ELEMENT_NAME_ACCEPTED
                val typeElements = GotoTypeDeclarationAction.findSymbolTypes(editor, offset, flags)

                typeElements?.mapNotNull { psiElement -> psiElement.getLspLocationForDefinition() } ?: emptyList()
            }
        }.forEach { location -> emit(location) }
    }
}
