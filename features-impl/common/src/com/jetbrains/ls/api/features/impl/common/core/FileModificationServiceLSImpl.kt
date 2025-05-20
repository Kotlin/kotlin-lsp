package com.jetbrains.ls.api.features.impl.common.core

import com.intellij.codeInsight.FileModificationService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal class FileModificationServiceLSImpl : FileModificationService() {
    override fun preparePsiElementsForWrite(elements: MutableCollection<out PsiElement>): Boolean = true
    override fun prepareFileForWrite(psiFile: PsiFile?): Boolean = true
    override fun prepareVirtualFilesForWrite(project: Project, files: MutableCollection<out VirtualFile>): Boolean = true
}