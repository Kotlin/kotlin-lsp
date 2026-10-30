// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.hover

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.util.uri
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.getExpectsForActual
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType

/**
 * Renders KDoc as Markdown: the primary tag text, then the tag sections in the order of the IDE `KDocRenderer.appendKDocSections`.
 * KDoc links become Markdown links to the resolved declarations.
 */
internal object LSKotlinKDocMarkdown {
    fun render(primaryTag: KDocTag, sections: List<KDocSection>): String {
        val content = primaryTag.contentWithResolvedLinks()
        // a `@constructor` primary tag is not repeated as a section
        val primaryTags = setOfNotNull(primaryTag, (primaryTag as? KDocSection)?.firstChild as? KDocTag)
        val renderedSections = renderSections(sections, primaryTags)
        if (renderedSections.isEmpty()) return content
        return (listOf(content.trim()) + renderedSections).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun renderSections(sections: List<KDocSection>, primaryTags: Set<KDocTag>): List<String> {
        val firstSection = sections.firstOrNull() ?: return emptyList()
        val namedDeclaration = PsiTreeUtil.getParentOfType(firstSection, KtNamedDeclaration::class.java, false, KtFile::class.java)

        fun findTagsByName(name: String): List<KDocTag> = sections.flatMap { it.findTagsByName(name) }.filter { it !in primaryTags }
        fun findTagByName(name: String): KDocTag? = findTagsByName(name).firstOrNull()

        val contextParameterNames = (namedDeclaration as? KtCallableDeclaration)?.contextParameters
                                      ?.filter { it.isContextParameter }
                                      ?.mapNotNullTo(HashSet()) { it.nameAsName?.asString() }
                                    ?: emptySet()
        val typeParameterNames = (namedDeclaration as? KtTypeParameterListOwner)?.typeParameters
                                   ?.mapNotNullTo(HashSet()) { it.nameAsName?.asString() }
                                 ?: emptySet()
        val paramTags = findTagsByName("param").filter { it.getSubjectName() != null }

        return buildList {
            addSection("kdoc.section.title.receiver", listOfNotNull(findTagByName("receiver")))
            addSection("kdoc.section.title.context.parameters", paramTags.filter { it.getSubjectName() in contextParameterNames })
            addSection("kdoc.section.title.parameters", paramTags.filter {
                val subjectName = it.getSubjectName()
                subjectName !in contextParameterNames && subjectName !in typeParameterNames
            })
            addSection("kdoc.section.title.type.parameters", paramTags.filter { it.getSubjectName() in typeParameterNames })
            addSection("kdoc.section.title.properties", findTagsByName("property").filter { it.getSubjectName() != null })
            addSection("kdoc.section.title.constructor", listOfNotNull(findTagByName("constructor")))
            addSection("kdoc.section.title.returns", listOfNotNull(findTagByName("return")))
            addSection("kdoc.section.title.throws", (findTagsByName("throws") + findTagsByName("exception")).filter { it.getSubjectName() != null })
            addSection("kdoc.section.title.author", findTagsByName("author"))
            addSection("kdoc.section.title.since", listOfNotNull(findTagByName("since")))
            addSection("kdoc.section.title.suppress", listOfNotNull(findTagByName("suppress")))
            addSection("kdoc.section.title.see.also", findTagsByName("see"))
        }
    }

    private fun MutableList<String>.addSection(titleKey: String, tags: List<KDocTag>) {
        val items = tags.map { renderItem(it) }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return
        add((listOf("**${KotlinBundle.message(titleKey)}:**") + items).joinToString("\n\n"))
    }

    /** `` `name` – text `` for a tag with a subject (the name links to its target when that resolves), the text alone otherwise. */
    private fun renderItem(tag: KDocTag): String {
        val text = tag.contentWithResolvedLinks().trim()
        val subjectName = tag.getSubjectName() ?: return text
        val subject = tag.getSubjectLink()?.let { resolveToUri(it) }?.let { "[`$subjectName`]($it)" } ?: "`$subjectName`"
        return if (text.isEmpty()) subject else "$subject – $text"
    }

    fun KDocTag.contentWithResolvedLinks(): String {
        val kDocLinks = buildMap {
            this@contentWithResolvedLinks.forEachDescendantOfType<KDocLink> {
                this[it.getLinkText()] = it
            }
        }

        fun mdLink(kDocLink: String, label: String = kDocLink): String {
            val resolvedLink = kDocLinks[kDocLink]?.let { resolveToUri(it) } ?: return label
            return "[$label]($resolvedLink)"
        }

        return getContent()
            .replace(LABELED_MARKDOWN_LINK) { mdLink(it.groupValues[2], it.groupValues[1]) }
            .replace(MARKDOWN_LINK) { mdLink(it.groupValues[1]) }
    }

    private fun resolveToUri(kDocLink: KDocLink): String? {
        val psi = resolveToPsi(kDocLink) ?: return null
        val path = psi.containingFile?.virtualFile ?: return null
        val fileDocument = psi.containingFile?.fileDocument ?: return null
        val startPosition = fileDocument.positionByOffset(psi.textOffset)

        // the format is taken from https://github.com/microsoft/vscode/blob/b3ec8181fc49f5462b5128f38e0723ae85e295c2/src/vs/platform/opener/common/opener.ts#L151-L160
        return "${path.uri.uri}#${startPosition.line + 1},${startPosition.character + 1}"
    }

    private fun resolveToPsi(kDocLink: KDocLink): PsiElement? {
        val lastNameSegment = kDocLink.children.filterIsInstance<KDocName>().lastOrNull() ?: return null
        return analyze(kDocLink) {
            val symbol = lastNameSegment.mainReference.resolveToSymbols().firstOrNull() ?: return null
            // a library can have both an `expect` declaration and its `actual` one, and either of them may resolve first;
            // the `expect` one keeps the link stable
            val expectSymbol = (symbol as? KaDeclarationSymbol)?.takeIf { it.isActual }?.getExpectsForActual()?.firstOrNull()
            (expectSymbol ?: symbol).sourcePsiSafe()
        }
    }

    private val MARKDOWN_LINK = Regex("\\[(.+?)](?!\\()")
    private val LABELED_MARKDOWN_LINK = Regex("\\[(.+?)]\\[(.+?)]")
}
