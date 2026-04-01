// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.callHierarchy

import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.TypeConversionUtil
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.impl.common.callHierarchy.LSCallHierarchyProviderBase.CallHierarchyItemData
import com.jetbrains.ls.api.features.impl.common.callHierarchy.LSCallHierarchyProviderBase.NameData
import com.jetbrains.ls.api.features.impl.common.callHierarchy.LSCallHierarchyRenderer
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.symbols.getKind
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.CallHierarchyItem
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.SymbolTag
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.asJava.elements.KtLightElementBase
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal object LSKotlinCallHierarchyRenderer : LSCallHierarchyRenderer {
    override val supportedLanguages: Set<LSLanguage>
        get() = setOf(LSKotlinLanguage)

    override fun createCallHierarchyItem(descriptor: ReferenceAwareNodeDescriptor): CallHierarchyItem? {
        val enclosingElement = descriptor.enclosingElement ?: return null

        // Element either appeared from java resolve or from kotlin
        val element = if (enclosingElement is KtLightElementBase) enclosingElement.navigationElement else enclosingElement

        val declaration = element as? KtNamedDeclaration ?: return null

        val presentation = descriptor.getPresentation() ?: return null

        val className = getContainingClassName(declaration) ?: return null
        val nameData = getNameData(declaration, className) ?: return null

        val containingFile = declaration.containingFile as? KtFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val document = containingFile.fileDocument

        val kind = declaration.getKind() ?: return null
        val range = declaration.textRange.toLspRange(document)
        val selectionRange = (declaration as? PsiNameIdentifierOwner)
            ?.nameIdentifier?.textRange?.toLspRange(document) ?: range

        val isDeprecated = declaration.annotationEntries.any { it.shortName?.asString() == "Deprecated" }

        return CallHierarchyItem(
            name = presentation,
            kind = kind,
            tags = if (isDeprecated) listOf(SymbolTag.Deprecated) else null,
            detail = containingFile.packageFqName.asString().takeIf { it.isNotEmpty() },
            uri = DocumentUri(virtualFile.uri),
            range = range,
            selectionRange = selectionRange,
            data = LSP.json.encodeToJsonElement(
                CallHierarchyItemData(
                    qualifiedClassName = className,
                    nameData = nameData,
                    filePath = virtualFile.path,
                    configurationEntryId = LSKotlinCallHierarchyProvider.uniqueId,
                ),
            ),
        )
    }

    private fun getContainingClassName(element: KtNamedDeclaration): String? {
        if (element is KtClassOrObject) {
            return element.fqName?.asString()
        }
        val containingClass = element.containingClassOrObject
        if (containingClass != null) {
            return containingClass.fqName?.asString()
        }

        val ktFile = element.containingFile as? KtFile ?: return null
        return JvmFileClassUtil.getFileClassInfoNoResolve(ktFile).facadeClassFqName.asString()
    }

    private fun getNameData(element: KtNamedDeclaration, className: String): NameData? {
        return when (element) {
            is KtNamedFunction -> {
                val name = element.name ?: return null
                val parameterTypes = element.getParametersPresentation() ?: return null
                NameData.MethodNameData(className, name, parameterTypes, isConstructor = false)
            }
            is KtConstructor<*> -> {
                val containingClassName = element.containingClassOrObject?.name ?: return null
                val parameterTypes = element.getParametersPresentation() ?: return null
                NameData.MethodNameData(className, containingClassName, parameterTypes, isConstructor = true)
            }
            is KtProperty -> {
                val name = element.name ?: return null
                NameData.FieldNameData(className, name)
            }
            is KtClassOrObject -> {
                val name = element.name ?: return null
                NameData.ClassNameData(className, name)
            }
            else -> null
        }
    }

    fun KtFunction.getParametersPresentation(): List<String>? {
        val lightMethod = this.toLightMethods().firstOrNull() ?: return null
        return lightMethod.parameterList.parameters.map { parameter ->
            val type = parameter.type
            TypeConversionUtil.erasure(type).canonicalText
        }
    }
}
