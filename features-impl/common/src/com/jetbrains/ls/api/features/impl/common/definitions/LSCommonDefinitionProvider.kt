// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.definitions

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.stubs.StubIndex
import com.jetbrains.analyzer.java.JavaFilePackageIndex
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isFromLibrary
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.definition.LSDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.common.utils.getTargetsAtPosition
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Range
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonDefinitionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val targetKinds: Set<TargetKind>
) : LSDefinitionProvider {
    context(_: LSServer, _: LspHandlerContext)
    override fun provideDefinitions(params: DefinitionParams): Flow<Location> = flow {
        val uri = params.textDocument.uri.uri
        withAnalysisContext(uri) {
            readAction {
                val file = uri.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = file.findPsiFile(project) ?: return@readAction emptyList()
                val document = file.findDocument() ?: return@readAction emptyList()
                val targets = psiFile.getTargetsAtPosition(params.position, document, targetKinds)

                targets.mapNotNull {
                    when (it) {
                        is PsiPackage -> it.directory?.uri?.let { Location(DocumentUri(it), Range.BEGINNING) }
                        else -> it.getLspLocationForDefinition()
                    }
                }
            }
        }.forEach { emit(it) }
    }
}

// A temporary replacement for PsiPackage.directories which is not working because of the missing logic in FakePackageIndexImpl.
// THis one works only for Java sources and JAR dependencies, Kotlin packages are handled in LSKotlinPackageDefinitionProvider.
private val PsiPackage.directory: VirtualFile?
    get() {
        return StubIndex.getInstance()
            .getContainingFilesIterator(JavaFilePackageIndex.FILE_PACKAGE_INDEX, qualifiedName, project, EverythingGlobalScope())
            .asSequence()
            .filterNot { it.isFromLibrary() }
            .mapNotNull { it.parent }
            .firstOrNull()
    }
