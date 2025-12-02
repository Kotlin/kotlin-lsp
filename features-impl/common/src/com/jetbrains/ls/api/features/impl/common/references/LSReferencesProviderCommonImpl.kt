// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.references

import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.model.psi.PsiSymbolService
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.common.utils.getTargetsAtPosition
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.references.LSReferencesProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.ReferenceParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class LSReferencesProviderCommonImpl(
    override val supportedLanguages: Set<LSLanguage>,
    private val targetKinds: Set<TargetKind>
) : LSReferencesProvider {
    context(_: LSServer, _: LspHandlerContext)
    override fun getReferences(params: ReferenceParams): Flow<Location> = channelFlow {
        withAnalysisContext {
            readAction a@{
                val file = params.findVirtualFile() ?: return@a
                val psiFile = file.findPsiFile(project) ?: return@a
                val document = file.findDocument() ?: return@a
                val targets = psiFile.getTargetsAtPosition(params.position, document, targetKinds)
                if (targets.isEmpty()) return@a

                val findUsagesManager = FindUsagesManager(project)

                // TODO LSP-241 handle all of them
                val target = targets.first()
                val handler = findUsagesManager.getFindUsagesHandler(target, true/*forbid showing dialogs*/) ?: return@a

                if (params.context.includeDeclaration) {
                    target.getLspLocationForDefinition()?.let {
                        runBlockingCancellable {
                            channel.send(it)
                        }
                    }
                }
                handler.processElementUsages(
                    target, { result ->
                        val location = result.element?.getLspLocationForDefinition() ?: return@processElementUsages true

                        runBlockingCancellable {
                            channel.send(location)
                        }
                        true
                    },
                    handler.findUsagesOptions
                )
            }
        }
    }
}
