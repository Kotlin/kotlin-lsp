// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.relatedTests

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.testIntegration.TestFinderHelper
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getLspLocationForDefinition
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.relatedTests.LSRelatedTestsProvider
import com.jetbrains.ls.api.features.relatedTests.RelatedTestsParams
import com.jetbrains.ls.api.features.relatedTests.RelatedTestsResult
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range

/**
 * Go to Test through the registered `testFinder` extensions, as `GotoTestOrCodeHandler` does: the element at the caret,
 * its source element from [TestFinderHelper.findSourceElement], then [TestFinderHelper.findClassesForTest] for a test
 * ([TestFinderHelper.isTest]: a test framework recognizes the class) or [TestFinderHelper.findTestsForClass] otherwise, in
 * the order the finders give (each sorts by name proximity). Test is a framework notion, not a source root one: a helper
 * class without tests in a test root is not a test here, as in the monolith.
 *
 * Cost: one read action. The finders search by name in the stub indexes; the test search of the Java finder matches
 * every short class name of the project against the class name, so it is for a user action, not for a pass.
 */
class LSCommonRelatedTestsProvider(
    override val supportedLanguages: Set<LSLanguage>,
) : LSRelatedTestsProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getRelatedTests(params: RelatedTestsParams): RelatedTestsResult? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val selected = PsiUtilCore.getElementAtOffset(psiFile, document.offsetByPosition(params.position))
                val isTest = TestFinderHelper.isTest(selected)
                val source = TestFinderHelper.findSourceElement(selected)
                    ?: return@readAction RelatedTestsResult(isTest, emptyList())
                val targets = if (isTest) TestFinderHelper.findClassesForTest(selected) else TestFinderHelper.findTestsForClass(selected)
                RelatedTestsResult(isTest, targets.mapNotNull { it.location() }.distinct(), nameOf(source))
            }
        }
    }

    /** The name of a target, or the start of a target file (the monolith opens the file there). */
    private fun PsiElement.location(): Location? {
        if (this is PsiFile) {
            val file = virtualFile ?: return null
            return Location(DocumentUri(file.uri), Range(Position(0, 0), Position(0, 0)))
        }
        return getLspLocationForDefinition()
    }

    /** As the monolith chooser title takes it (`GotoTargetHandler`). */
    private fun nameOf(source: PsiElement): String? = (source as? NavigationItem)?.name
}
