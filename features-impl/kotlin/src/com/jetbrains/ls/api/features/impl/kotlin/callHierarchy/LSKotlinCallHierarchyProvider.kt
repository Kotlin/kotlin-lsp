// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.callHierarchy

import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.callHierarchy.LSCallHierarchyProviderBase
import com.jetbrains.ls.api.features.impl.common.utils.findElementUnderCaret
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCallHierarchyNodeDescriptor
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCalleeTreeStructure
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCallerTreeStructure
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.getElementForCallHierarchy
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtElement

private val WHITESPACE = Regex("\\s+")

internal object LSKotlinCallHierarchyProvider : LSCallHierarchyProviderBase<KtElement>() {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId = LSUniqueConfigurationEntry.UniqueId("kotlin.callHierarchy")
    override fun getIncomingCallsTreeStructure(
        project: Project,
        restoredElement: KtElement,
        scope: String
    ): HierarchyTreeStructure {
        return KotlinCallerTreeStructure(restoredElement, scope)
    }

    override fun getOutgoingCallsTreeStructure(
        project: Project,
        restoredElement: KtElement,
        scope: String
    ): HierarchyTreeStructure {
        return KotlinCalleeTreeStructure(restoredElement, scope)
    }

    override fun getRootDescriptor(
        project: Project,
        document: Document,
        offset: Int
    ): ReferenceAwareNodeDescriptor? {
        val editor = ImaginaryEditor(project, document)
        val elementUnderCaret = findElementUnderCaret(editor, offset)
        val target = getElementForCallHierarchy(elementUnderCaret) ?: return null
        return KotlinCallHierarchyNodeDescriptor(null, target, true, false)
    }

    override fun resolvePsiMember(
        data: CallHierarchyItemData,
        project: Project
    ): KtElement? {
        val target = data.target as? CallHierarchyTarget.Declaration ?: return null
        return resolveDeclaration(target, project) as? KtElement
    }

    override fun signatureOf(element: PsiElement): String? {
        if (element !is KtCallableDeclaration) return null
        val parameterTypes = element.valueParameters.map { parameter ->
            val type = parameter.typeReference?.text
            if (parameter.isVarArg) "vararg $type" else type
        }
        // Type parameters with bounds and `where` constraints: `<T : Number> f(T)` and `<T : CharSequence> f(T)` are overloads.
        val typeParameters = element.typeParameterList?.text?.let { "$it " }.orEmpty()
        val constraints = element.typeConstraintList?.text?.let { " where $it" }.orEmpty()
        val signature = typeParameters + signature(element.receiverTypeReference?.text, parameterTypes) + constraints
        return signature.replace(WHITESPACE, " ")
    }
}
