// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.util.lspUriToIntellijUri
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.lsp.protocol.URI
import kotlinx.serialization.Serializable

private val LOG = logger<PsiSerializablePointer>()

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
        fun fromPsiPointer(pointer: SmartPsiElementPointer<*>): PsiSerializablePointer? {
            val element = pointer.element
            if (element == null) {
                LOG.warn("Cannot serialize PSI pointer because its element is unavailable: $pointer")
                return null
            }

            val psiFile = pointer.containingFile
            if (psiFile == null) {
                LOG.warn("Cannot serialize PSI pointer to ${element::class.java.name} because its containing file is unavailable")
                return null
            }

            val virtualFile = psiFile.virtualFile
            if (virtualFile == null) {
                LOG.warn("Cannot serialize PSI pointer because its containing file has no virtual file")
                return null
            }

            if (element !is PsiFile && element.textRange == null) {
                LOG.warn("Cannot serialize PSI pointer to ${element::class.java.name} because it has no text range")
                return null
            }
            return create(element, virtualFile)
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
