// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.callHierarchy

import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx.SCOPE_ALL
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.callHierarchy.LSCallHierarchyProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCall
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyItem
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCall
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyPrepareParams
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

private val LOG = logger<LSCallHierarchyProviderBase<*>>()

private val WHITESPACE = Regex("\\s+")

abstract class LSCallHierarchyProviderBase<Element : PsiElement> : LSCallHierarchyProvider {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    override suspend fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val rootDescriptor = getRootDescriptor(project, document, offset) ?: return@readAction null
                val item = LSCallHierarchyRenderer.createCallHierarchyItemFor(rootDescriptor) ?: return@readAction null
                listOf(item)
            }
        }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    override suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall>? {
        val itemData = CallHierarchyItemData.fromJson(params.item.data) ?: return null
        return server.withAnalysisContext {
            readAction {
                val member = resolvePsiMember(itemData, project) ?: return@readAction null

                val treeStructure = getIncomingCallsTreeStructure(project, member, SCOPE_ALL)
                val children = treeStructure.getChildElements(treeStructure.rootElement)

                children.mapNotNull { child ->
                    val descriptor = child as? ReferenceAwareNodeDescriptor ?: return@mapNotNull null
                    val callerMember = descriptor.enclosingElement ?: return@mapNotNull null
                    val callerItem = LSCallHierarchyRenderer.createCallHierarchyItemFor(descriptor) ?: return@mapNotNull null

                    val references = descriptor.references
                    val callerDocument = callerMember.containingFile.fileDocument

                    val fromRanges = references.mapNotNull { ref -> getIncomingCallRange(ref)?.toLspRange(callerDocument) }

                    CallHierarchyIncomingCall(from = callerItem, fromRanges = fromRanges)
                }
            }
        }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    override suspend fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall>? {
        val itemData = CallHierarchyItemData.fromJson(params.item.data) ?: return null
        return server.withAnalysisContext {
            readAction {
                val member = resolvePsiMember(itemData, project) ?: return@readAction null

                val treeStructure = getOutgoingCallsTreeStructure(project, member, SCOPE_ALL)
                val children = treeStructure.getChildElements(treeStructure.rootElement)
                val document = member.containingFile.fileDocument

                children.mapNotNull { child ->
                    val descriptor = child as? ReferenceAwareNodeDescriptor ?: return@mapNotNull null
                    val calleeItem = LSCallHierarchyRenderer.createCallHierarchyItemFor(descriptor) ?: return@mapNotNull null

                    val fromRanges = getOutgoingCallRanges(descriptor).map { it.toLspRange(document) }

                    CallHierarchyOutgoingCall(to = calleeItem, fromRanges = fromRanges)
                }
            }
        }
    }

    /**
     * The range of the call that [reference] belongs to, for the `fromRanges` of an incoming call.
     * By default, it is the name identifier of the reference element, or the parent of the reference element, such as a call expression.
     */
    protected open fun getIncomingCallRange(reference: PsiReference): TextRange? {
        val refElement = reference.element
        val callElement = if (refElement is PsiNameIdentifierOwner) refElement.nameIdentifier else refElement.parent
        return callElement?.textRange
    }

    /**
     * The ranges of the calls to [descriptor] in the requested element, for the `fromRanges` of an outgoing call.
     * By default, they are the ranges of the descriptor references.
     */
    protected open fun getOutgoingCallRanges(descriptor: ReferenceAwareNodeDescriptor): List<TextRange> =
        descriptor.references.map { it.absoluteRange }

    protected abstract fun getIncomingCallsTreeStructure(project: Project, restoredElement: Element, scope: String): HierarchyTreeStructure

    protected abstract fun getOutgoingCallsTreeStructure(project: Project, restoredElement: Element, scope: String): HierarchyTreeStructure

    /**
     * Constructs the descriptor for the root node of the call tree.
     */
    protected abstract fun getRootDescriptor(project: Project, document: Document, offset: Int): ReferenceAwareNodeDescriptor?

    /**
     * Restores the element for which children nodes should be calculated during `callHierarchy/incomingCalls` and `outgoing/outgoingCalls`
     * requests.
     */
    protected abstract fun resolvePsiMember(data: CallHierarchyItemData, project: Project): Element?

    /**
     * The text of the parameter types (and the receiver type) of [element], from PSI only, which tells the same-name siblings
     * apart, such as overloads; `null` for elements that cannot have such siblings, such as classes and fields.
     */
    abstract fun signatureOf(element: PsiElement): String?

    /**
     * Restores the declaration of [target]. If an edit has moved the pointer to a same-name sibling with another signature,
     * such as an overload inserted above, finds the sibling with the signature of [target], or returns `null`.
     */
    protected fun resolveDeclaration(target: CallHierarchyTarget.Declaration, project: Project): PsiElement? {
        val restored = target.pointer.restore(project) ?: return null
        if (target.signature == null || signatureOf(restored) == target.signature) return restored
        val name = (restored as? PsiNamedElement)?.name
        val siblings = restored.parent?.children ?: return null
        return siblings.singleOrNull { sibling ->
            sibling.javaClass == restored.javaClass && (sibling as? PsiNamedElement)?.name == name && signatureOf(sibling) == target.signature
        }
    }

    /** `Receiver.(A, B)`, or `(A, B)` without a receiver, with the whitespace collapsed. */
    protected fun signature(receiverType: String?, parameterTypes: List<String?>): String {
        val receiver = receiverType?.let { "$it." }.orEmpty()
        return "$receiver(${parameterTypes.joinToString()})".replace(WHITESPACE, " ")
    }


    @Serializable
    data class CallHierarchyItemData(
        val target: CallHierarchyTarget,
        val filePath: String,
        override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
    ) : ResolveDataWithConfigurationEntryId {
        companion object {
            fun fromJson(jsonElement: JsonElement?): CallHierarchyItemData? {
                if (jsonElement == null) return null
                return try {
                    LSP.json.decodeFromJsonElement(serializer(), jsonElement)
                } catch (e: Exception) {
                    LOG.warn("Unable to deserialize CallHierarchyItemData from JSON, maybe it comes from another server version", e)
                    return null
                }
            }
        }
    }

    /**
     * The member a call hierarchy item stands for.
     */
    @Serializable
    sealed interface CallHierarchyTarget {
        /**
         * Source declaration the item stands for.
         */
        @Serializable
        data class Declaration(
            val pointer: PsiSerializablePointer,
            /** [signatureOf] the declaration, to find it again among its same-name siblings. */
            val signature: String? = null,
        ) : CallHierarchyTarget

        /**
         * Java member with no source (implicit default constructor, record canonical constructor, accessor or field).
         */
        @Serializable
        data class ImplicitMember(
            val classPointer: PsiSerializablePointer,
            val name: String,
            /** Not `kind`: [LSP.json] uses `kind` as the class discriminator. */
            val memberKind: Kind,
            /** Erased canonical text of the parameter types; empty for fields. */
            val parameterTypes: List<String>,
        ) : CallHierarchyTarget {
            enum class Kind { METHOD, CONSTRUCTOR, FIELD }
        }
    }
}
