// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.callHierarchy

import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.callHierarchy.LSCallHierarchyProviderBase
import com.jetbrains.ls.api.features.impl.common.utils.findElementUnderCaret
import com.jetbrains.ls.api.features.impl.kotlin.callHierarchy.LSKotlinCallHierarchyRenderer.getParametersPresentation
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCallHierarchyNodeDescriptor
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCalleeTreeStructure
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.KotlinCallerTreeStructure
import org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.getElementForCallHierarchy
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

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
        val nameData = data.nameData
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(nameData.className, GlobalSearchScope.allScope(project))
            ?: return null

        val ktSource = psiClass.navigationElement
        val declarations = when (ktSource) {
            is KtClassOrObject -> ktSource.declarations
            is KtFile -> ktSource.declarations
            else -> return null
        }

        return when (nameData) {
            is NameData.ClassNameData -> {
                ktSource as? KtClassOrObject
            }
            is NameData.FieldNameData -> {
                declarations.filterIsInstance<KtProperty>().find { it.name == nameData.memberName }
            }
            is NameData.MethodNameData -> {
                if (nameData.isConstructor) {
                    declarations.filterIsInstance<KtConstructor<*>>().find {
                        matchParameters(it, nameData.parametersName)
                    }
                } else {
                    declarations.filterIsInstance<KtNamedFunction>().find {
                        it.name == nameData.memberName && matchParameters(it, nameData.parametersName)
                    }
                }
            }
        }
    }
}

private fun matchParameters(function: KtFunction, parameterTypes: List<String>): Boolean {
    return function.getParametersPresentation() == parameterTypes
}