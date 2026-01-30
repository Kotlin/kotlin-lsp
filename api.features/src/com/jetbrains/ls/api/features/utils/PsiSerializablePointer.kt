// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.*
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.util.lspUriToIntellijUri
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.lsp.protocol.URI
import kotlinx.serialization.Serializable

@Serializable
sealed class PsiSerializablePointer {
    internal abstract val uri: URI
    internal abstract val elementClass: String

    abstract fun restore(psiFile: PsiFile): PsiElement?

    open fun restore(project: Project): PsiElement? {
        val intellijUri = uri.lspUriToIntellijUri() ?: return null
        val virtualFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl(intellijUri) ?: return null
        val psiFile = virtualFile.findPsiFile(project) ?: return null
        return restore(psiFile)
    }

     abstract fun matches(element: PsiElement): Boolean

    @Serializable
    internal data class PsiFileSerializablePointer(
        override val uri: URI,
        override val elementClass: String,
    ) : PsiSerializablePointer() {

        override fun restore(psiFile: PsiFile): PsiElement? {
            return psiFile
        }

        override fun matches(element: PsiElement): Boolean {
            return element::class.java.name == elementClass
        }
    }

    @Serializable
    internal data class PsiElementSerializablePointer(
        override val uri: URI,
        override val elementClass: String,
        val startOffset: Int,
        val endOffset: Int,
    ) : PsiSerializablePointer() {
        override fun restore(psiFile: PsiFile): PsiElement? {
            var candidate: PsiElement? = psiFile.findElementAt(startOffset) ?: return null
            while (candidate != null && candidate !is PsiFileSystemItem) {
                if (matches(candidate)) {
                    return candidate
                }
                candidate = candidate.parent
            }
            return null
        }

        override fun matches(element: PsiElement): Boolean {
            return element::class.java.name == elementClass
                    && element.startOffset == startOffset
                    && element.endOffset == endOffset
        }
    }

    companion object {
        fun fromPsiPointer(pointer: SmartPsiElementPointer<*>): PsiSerializablePointer {
            val psiFile = pointer.containingFile ?: error("File for pointer is null")
            val element = pointer.element ?: error("Element for pointer is null")
            return create(element, psiFile.virtualFile)
        }

        fun create(psi: PsiElement, file: VirtualFile): PsiSerializablePointer {
            return when (psi) {
                is PsiFile -> {
                    PsiFileSerializablePointer(file.uri, psi::class.java.name)
                }

                else -> PsiElementSerializablePointer(
                    uri = file.uri,
                    elementClass = psi::class.java.name,
                    startOffset = psi.textRange.startOffset,
                    endOffset = psi.textRange.endOffset,
                )
            }
        }
    }
}

fun PsiSerializablePointer.toPsiPointer(project: Project): SmartPsiElementPointer<PsiElement> {
    val element = restore(project) ?: error("Cannot restore ${this} for pointer")
    return element.createSmartPointer()
}