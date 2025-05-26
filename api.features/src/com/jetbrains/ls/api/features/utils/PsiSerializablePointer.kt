// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.protocol.URI
import kotlinx.serialization.Serializable

@Serializable
sealed class PsiSerializablePointer {
    internal abstract val uri: URI
    internal abstract val elementClass: String

    abstract fun restore(psiFile: PsiFile): PsiElement?

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
        context(LSAnalysisContext, LSServer)
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