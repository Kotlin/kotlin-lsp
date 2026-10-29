// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType

/**
 * Resolves a `psi_element://` link of a Kotlin declaration's rendered KDoc through the matching link of its own doc comment.
 *
 * `KotlinDocumentationLinkHandler` resolves the link text in a code fragment, which the server cannot analyze; it stays the
 * fallback for links that do not come from the doc comment.
 */
internal class LSKotlinDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (!url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)) return null
        val declaration = (target.navigatable as? PsiElement)?.unwrapped as? KtDeclaration ?: return null
        val linkText = url.removePrefix(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
        val link = declaration.docComment?.findDescendantOfType<KDocLink> { it.getLinkText() == linkText } ?: return null
        val name = link.children.filterIsInstance<KDocName>().lastOrNull() ?: return null
        val resolved = analyze(name) { name.mainReference.resolveToSymbols().firstNotNullOfOrNull { it.psi } } ?: return null
        val resolvedTarget = psiDocumentationTargets(resolved, resolved).firstOrNull() ?: return null
        return LinkResolveResult.resolvedTarget(resolvedTarget)
    }
}
