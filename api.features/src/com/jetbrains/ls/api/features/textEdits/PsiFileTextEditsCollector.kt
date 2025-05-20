package com.jetbrains.ls.api.features.textEdits

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.lsp.protocol.TextEdit

object PsiFileTextEditsCollector {
    context(LSAnalysisContext)
    fun collectTextEdits(
        file: VirtualFile,
        modificationAction: (file: PsiFile) -> Unit
    ): List<TextEdit> {
        val originalPsi = file.findPsiFile(project)
            ?: error("Can't find PSI file for file ${file.uri}")
        val fileForModification =
            FileForModificationFactory.forLanguage(originalPsi.language).createFileForModifications(originalPsi, setOriginalFile = false)

        val document: Document = fileForModification.virtualFile.findDocument()
            ?: error("Can't find document for file ${file.uri}")
        val textBeforeCommand = document.text
        CommandProcessor.getInstance().executeCommand(
            project,
            { modificationAction(fileForModification) },
            @Suppress("HardCodedStringLiteral") "Collecting Text Edits",
            null,
        )
        val textAfterCommand = document.text
        return TextEditsComputer.computeTextEdits(textBeforeCommand, textAfterCommand)
    }

    interface FileForModificationFactory {
        fun createFileForModifications(file: PsiFile, setOriginalFile: Boolean): PsiFile

        private object Extension : LanguageExtension<FileForModificationFactory>("ls.fileForModificationFactory")

        companion object {
            fun forLanguage(language: Language): FileForModificationFactory =
                Extension.forLanguage(language)
        }
    }
}