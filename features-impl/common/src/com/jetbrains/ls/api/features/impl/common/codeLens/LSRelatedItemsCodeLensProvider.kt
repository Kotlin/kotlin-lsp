// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.codeLens

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.ide.plugins.ContentModuleDescriptor
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.jetbrains.analyzer.plugins.isUserPlugin
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getLspLocationForDefinition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeLens.LSCodeLensProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.VisibleForTesting
import java.util.Collections
import java.util.IdentityHashMap

/** The arguments of the [NAVIGATE_COMMAND_NAME] command: the popup title and the targets. */
@VisibleForTesting
@Serializable
data class NavigateArgs(
    val title: String,
    val locations: List<Location>,
)

/**
 * A code lens for each [RelatedItemLineMarkerInfo] that a user plugin's [RelatedItemLineMarkerProvider] puts on a file.
 * The desktop IDE shows these markers as gutter icons that navigate to the related items, for example a DevKit class to its `plugin.xml` registration.
 * The server has no gutter, so the marker becomes a lens: the anchor is the marker range, the command is [NAVIGATE_COMMAND_NAME] with [NavigateArgs].
 * The client shows the lens as a gutter icon and navigates, or shows a popup when there are several targets.
 *
 * Only the providers of a user plugin take part, see [acceptsPlugin] and [USER_PLUGINS_ONLY].
 * A file with more than [MAX_SYMBOLS] named declarations gets no lenses, like the client-side inheritance markers.
 * Gated by [com.jetbrains.ls.snapshot.api.impl.core.LSSessionConfig.clientSupportsRelatedItemsCodeLens].
 */
class LSRelatedItemsCodeLensProvider(
    override val supportedLanguages: Set<LSLanguage>,
    /** Accepts the plugin behind a provider. The default is [USER_PLUGINS_ONLY]; a test passes a wider rule. */
    private val acceptsPlugin: (PluginMainDescriptor) -> Boolean = USER_PLUGINS_ONLY,
) : LSCodeLensProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeLenses(params: CodeLensParams): Flow<CodeLens> = flow {
        if (!server.config.clientSupportsRelatedItemsCodeLens) return@flow
        val lenses: List<CodeLens> = server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.uri.uri.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val providers = relatedItemProviders(psiFile)
                if (providers.isEmpty()) return@readAction emptyList()
                val elements = collectElements(psiFile) ?: return@readAction emptyList()
                val markers = ArrayList<LineMarkerInfo<*>>()
                providers.forEach { it.collectSlowLineMarkers(elements, markers) }
                markers.filterIsInstance<RelatedItemLineMarkerInfo<*>>().mapNotNull { lens(it, document) }
            }
        }
        emitAll(lenses.asFlow())
    }

    /** The accepted [RelatedItemLineMarkerProvider]s for the language of [psiFile], like `LineMarkersPass.getMarkerProviders`. */
    private fun relatedItemProviders(psiFile: PsiFile): List<RelatedItemLineMarkerProvider> {
        val accepted: Set<LineMarkerProvider> = LineMarkerProviders.EP_NAME.extensionList
            .filter { bean -> bean.pluginDescriptor.mainDescriptor()?.let(acceptsPlugin) == true }
            .mapTo(Collections.newSetFromMap(IdentityHashMap())) { it.instance }
        if (accepted.isEmpty()) return emptyList()
        return LineMarkerProviders.getInstance().allForLanguageOrAny(psiFile.language)
            .filterIsInstance<RelatedItemLineMarkerProvider>()
            .filter { provider -> provider in accepted }
    }

    /**
     * Every element of [psiFile] in tree order, the input the desktop `LineMarkersPass` gives a provider.
     * Null when the file has more than [MAX_SYMBOLS] named declarations.
     */
    private fun collectElements(psiFile: PsiFile): List<PsiElement>? {
        val elements = ArrayList<PsiElement>()
        var symbols = 0
        var capped = false
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiNameIdentifierOwner && ++symbols > MAX_SYMBOLS) {
                    capped = true
                    stopWalking()
                    return
                }
                elements.add(element)
                super.visitElement(element)
            }
        })
        if (capped) {
            LOG.info("Skipped the related item lenses for ${psiFile.name}: more than $MAX_SYMBOLS symbols")
            return null
        }
        return elements
    }

    private fun lens(marker: RelatedItemLineMarkerInfo<*>, document: Document): CodeLens? {
        val range = marker.element?.textRange ?: return null
        val locations: List<Location> = marker.createGotoRelatedItems().mapNotNull { it.element?.getLspLocationForDefinition() }
        if (locations.isEmpty()) return null
        // The lens title carries the icon token; the popup title in the arguments stays plain.
        val title: @Nls String = marker.lineMarkerTooltip?.let(::plainText)?.ifEmpty { null } ?: LspServerBundle.message("code.lens.related.items")
        val command = Command(
            title = LspServerBundle.message("code.lens.related.items.title", title),
            command = NAVIGATE_COMMAND_NAME,
            arguments = listOf(LSP.json.encodeToJsonElement(NavigateArgs(title = title, locations = locations))),
        )
        return CodeLens(range.toLspRange(document), command, data = null)
    }

    /** A tooltip without its HTML: `NavigationGutterIconBuilder` wraps the target names in `<html>` and `<br>`. */
    private fun plainText(tooltip: @Nls String): @Nls String =
        StringUtil.unescapeXmlEntities(StringUtil.stripHtml(tooltip, " ")).replace(WHITESPACE, " ").trim()

    companion object {
        private val LOG = logger<LSRelatedItemsCodeLensProvider>()
        private val WHITESPACE = Regex("\\s+")

        /**
         * The client-side command the lens invokes.
         * The client opens the single location, or shows a popup with every location under the title.
         */
        const val NAVIGATE_COMMAND_NAME: String = "intellij.navigate"

        /**
         * The default plugin rule: only a user plugin's providers take part.
         * A user plugin comes from the `idea.plugins.path` directory or from `<dist>/third-party-plugins`, see [isUserPlugin].
         * The bundled plugins are the server's own analysis containers; their markers are IDE gutter features the lens does not mirror.
         */
        val USER_PLUGINS_ONLY: (PluginMainDescriptor) -> Boolean = ::isUserPlugin

        /** The cap on named declarations per file, like `lsp.inheritance.markers.max.symbols` on the client. */
        const val MAX_SYMBOLS: Int = 500

        /** The plugin behind an extension: the descriptor itself or the parent of its content module. */
        private fun PluginDescriptor.mainDescriptor(): PluginMainDescriptor? = when (this) {
            is PluginMainDescriptor -> this
            is ContentModuleDescriptor -> parent
            else -> null
        }
    }
}
