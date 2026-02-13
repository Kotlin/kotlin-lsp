// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.definitions

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.stubs.StubIndex
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isFromLibrary
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.definition.LSDefinitionProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Range
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex

internal object LSKotlinPackageDefinitionProvider : LSDefinitionProvider {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideDefinitions(params: DefinitionParams): Flow<Location> = flow {
        val uri = params.textDocument.uri.uri
        server.withAnalysisContext {
            readAction {
                val virtualFile = uri.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val offset = document.offsetByPosition(params.position)
                val reference = psiFile.findReferenceAt(offset) ?: return@readAction emptyList()
                val psiPackage = (reference.resolve() as? PsiPackage) ?: return@readAction emptyList()

                // A hackish replacement for PsiPackage.directories which is not working because of the missing logic in FakePackageIndexImpl.
                StubIndex.getInstance()
                    .getContainingFilesIterator(KotlinExactPackagesIndex.NAME, psiPackage.qualifiedName, project, EverythingGlobalScope())
                    .asSequence()
                    .filterNot { it.isFromLibrary() }
                    .mapNotNull { it.parent?.uri?.let { Location(DocumentUri(it), Range.BEGINNING) } }
                    .toList()
            }
        }.forEach { emit(it) }
    }
}