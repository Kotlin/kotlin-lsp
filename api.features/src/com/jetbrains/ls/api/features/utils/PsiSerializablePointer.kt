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
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.util.lspUriToIntellijUri
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer.PsiPathSerializablePointer.Segment
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

    /**
     * Points to a [PsiNameIdentifierOwner] by its [path] from the file, so it survives edits that move the element.
     * It breaks when an element on the path is renamed, or a sibling of the same class and name is added, removed or moved before it.
     */
    @Serializable
    internal data class PsiPathSerializablePointer(
        override val uri: URI,
        override val elementClass: String,
        /** From the top-level element down to the element itself. */
        val path: List<Segment>,
    ) : PsiSerializablePointer() {
        /** [ordinal] is the index among the path children of the parent with the same [elementClass] and [name]. */
        @Serializable
        data class Segment(val elementClass: String, val name: String?, val ordinal: Int)

        override fun restore(psiFile: PsiFile): PsiElement? {
            var element: PsiElement = psiFile
            for (segment in path) {
                element = pathChildren(element).filter { it.fits(segment) }.getOrNull(segment.ordinal) ?: return null
            }
            return element
        }

        override fun matches(element: PsiElement): Boolean {
            return element is PsiNameIdentifierOwner && pathOf(element) == path
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

        /**
         * Creates a [PsiPathSerializablePointer] for a [PsiNameIdentifierOwner], which survives edits that move [psi], or a
         * [create] pointer for any other element.
         */
        fun createByPath(psi: PsiElement, file: VirtualFile): PsiSerializablePointer {
            val path = (psi as? PsiNameIdentifierOwner)?.let(::pathOf) ?: return create(psi, file)
            return PsiPathSerializablePointer(file.uri, psi::class.java.name, path)
        }
    }
}

/** The path of [element] from its file, or `null` if an element on the path is not among the path children of its parent. */
private fun pathOf(element: PsiNameIdentifierOwner): List<Segment>? {
    val chain = generateSequence(element) { PsiTreeUtil.getParentOfType(it, PsiNameIdentifierOwner::class.java) }.toList().asReversed()
    var parent: PsiElement = element.containingFile ?: return null
    return chain.map { node ->
        val segment = Segment(node::class.java.name, node.name, ordinal = 0)
        val ordinal = pathChildren(parent).filter { it.fits(segment) }.indexOf(node)
        if (ordinal < 0) return null
        parent = node
        segment.copy(ordinal = ordinal)
    }
}

/** The [PsiNameIdentifierOwner]s inside [parent] with no other [PsiNameIdentifierOwner] between them and [parent]. */
private fun pathChildren(parent: PsiElement): List<PsiNameIdentifierOwner> {
    val result = mutableListOf<PsiNameIdentifierOwner>()
    fun collect(element: PsiElement) {
        var child = element.firstChild
        while (child != null) {
            if (child is PsiNameIdentifierOwner) result += child else collect(child)
            child = child.nextSibling
        }
    }
    collect(parent)
    return result
}

/** Whether this element has the class and the name of [segment]; the ordinal is not checked. */
private fun PsiNameIdentifierOwner.fits(segment: Segment): Boolean {
    return this::class.java.name == segment.elementClass && name == segment.name
}

fun PsiSerializablePointer.toPsiPointer(project: Project): SmartPsiElementPointer<PsiElement> {
    val element = restore(project) ?: error("Cannot restore ${this} for pointer")
    return element.createSmartPointer()
}
