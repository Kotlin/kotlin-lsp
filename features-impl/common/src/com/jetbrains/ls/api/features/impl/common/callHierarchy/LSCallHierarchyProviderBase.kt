// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.callHierarchy

import com.intellij.ide.hierarchy.HierarchyBrowserScopes.SCOPE_PROJECT
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.callHierarchy.LSCallHierarchyProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
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

                val treeStructure = getIncomingCallsTreeStructure(project, member, SCOPE_PROJECT)
                val children = treeStructure.getChildElements(treeStructure.rootElement)

                children.mapNotNull { child ->
                    val descriptor = child as? ReferenceAwareNodeDescriptor ?: return@mapNotNull null
                    val callerMember = descriptor.enclosingElement ?: return@mapNotNull null
                    val callerItem = LSCallHierarchyRenderer.createCallHierarchyItemFor(descriptor) ?: return@mapNotNull null

                    val references = descriptor.references
                    val callerDocument = callerMember.containingFile.fileDocument

                    val fromRanges = references.mapNotNull { ref ->
                        val refElement = ref.element
                        val callElement = if (refElement is PsiNameIdentifierOwner) refElement.nameIdentifier else refElement.parent
                        callElement?.textRange?.toLspRange(callerDocument)
                    }

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

                val treeStructure = getOutgoingCallsTreeStructure(project, member, SCOPE_PROJECT)
                val children = treeStructure.getChildElements(treeStructure.rootElement)
                val document = member.containingFile.fileDocument

                children.mapNotNull { child ->
                    val descriptor = child as? ReferenceAwareNodeDescriptor ?: return@mapNotNull null
                    val calleeItem = LSCallHierarchyRenderer.createCallHierarchyItemFor(descriptor) ?: return@mapNotNull null

                    val fromRanges = descriptor.references.map { ref ->
                        ref.absoluteRange.toLspRange(document)
                    }

                    CallHierarchyOutgoingCall(to = calleeItem, fromRanges = fromRanges)
                }
            }
        }
    }

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


    @Serializable
    data class CallHierarchyItemData(
        val qualifiedClassName: String,
        val nameData: NameData,
        val filePath: String,
        override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
    ) : ResolveDataWithConfigurationEntryId {
        companion object {
            fun fromJson(jsonElement: JsonElement?): CallHierarchyItemData? {
                if (jsonElement == null) return null
                return try {
                    LSP.json.decodeFromJsonElement(serializer(), jsonElement)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Stores the information about the name of the member for which "Call Hierarchy" request was invoked.
     */
    @Serializable
    sealed interface NameData {
        /**
         * FQ name of the containing class or the name of class itself
         */
        val className: String

        /**
         * Short name of the member
         */
        val memberName: String

        @Serializable
        data class ClassNameData(override val className: String, override val memberName: String) : NameData

        @Serializable
        data class MethodNameData(
            override val className: String,
            override val memberName: String,
            val parametersName: List<String>,
            val isConstructor: Boolean
        ) :
            NameData

        @Serializable
        data class FieldNameData(override val className: String, override val memberName: String) : NameData
    }
}
