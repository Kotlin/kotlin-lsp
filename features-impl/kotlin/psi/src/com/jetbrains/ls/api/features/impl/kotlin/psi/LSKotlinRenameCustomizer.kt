package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDirectoryContainer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.core.processors.LSRenameCustomizer
import com.jetbrains.ls.api.core.processors.RefactoringContext
import com.jetbrains.ls.api.core.processors.RenameContext
import com.jetbrains.ls.api.core.processors.RenameSingleDirectoryContext

class LSKotlinRenameCustomizer : LSRenameCustomizer {
    override fun createContext(target: PsiElement, newName: String, contextFile: PsiFile): RefactoringContext {
        if (target !is PsiPackage) return RenameContext(target, newName)

        val directory = target.findDirectoryInSameSourceRoot(contextFile)
            ?: return RenameContext(target, newName)
        return RenameSingleDirectoryContext(directory, newName)
    }
}

private fun PsiDirectoryContainer.findDirectoryInSameSourceRoot(contextFile: PsiFile): PsiDirectory? {
    val contextVirtualFile = contextFile.virtualFile ?: return null
    val fileIndex = ProjectFileIndex.getInstance(contextFile.project)
    val sourceRoot = fileIndex.getSourceRootForFile(contextVirtualFile) ?: return null
    return directories.firstOrNull { directory ->
        fileIndex.getSourceRootForFile(directory.virtualFile) == sourceRoot
    }
}
