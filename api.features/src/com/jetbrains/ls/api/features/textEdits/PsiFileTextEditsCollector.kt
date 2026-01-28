// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.textEdits

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.lsp.protocol.TextEdit
import kotlin.coroutines.cancellation.CancellationException

object PsiFileTextEditsCollector {

    private val logger = logger<PsiFileTextEditsCollector>()

    context(analysisContext: LSAnalysisContext)
    fun collectTextEdits(file: VirtualFile, modificationAction: (file: PsiFile) -> Unit): List<TextEdit> {
        val app = ApplicationManager.getApplication()
        app.assertReadAccessNotAllowed()

        return invokeAndWaitIfNeeded {
            runWriteAction {
                var res: List<TextEdit>? = null
                CommandProcessor.getInstance().executeCommand(
                    project,
                    {
                        val originalPsi = file.findPsiFile(project) ?: error("Can't find PSI file for file ${file.uri}")
                        val fileForModification = FileForModificationFactory.forLanguage(originalPsi.language)
                            .createFileForModifications(originalPsi)
                        val textBeforeCommand = fileForModification.text
                        try {
                            modificationAction(fileForModification)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (x: Throwable) {
                            logger.error("command failed", x)
                        }
                        val textAfterCommand = fileForModification.text
                        res = TextEditsComputer.computeTextEdits(textBeforeCommand, textAfterCommand)
                    },
                    @Suppress("HardCodedStringLiteral") "Collecting Text Edits",
                    null,
                )
                requireNotNull(res) {
                    "command was not run"
                }
            }
        }
    }

    interface FileForModificationFactory {
        fun createFileForModifications(file: PsiFile): PsiFile

        private object Extension : LanguageExtension<FileForModificationFactory>("ls.fileForModificationFactory")

        companion object {
            fun forLanguage(language: Language): FileForModificationFactory = Extension.forLanguage(language)
        }
    }
}
