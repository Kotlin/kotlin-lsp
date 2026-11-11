// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.surroundWith

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.lang.surroundWith.ModCommandSurrounder
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.parents
import com.intellij.refactoring.RefactoringBundle
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.selectionOf
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.stamps
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.annotations.Nls

/** The kind of the code action. As a `refactor` kind, it is listed in the light bulb and in the refactoring menu of a client. */
val SurroundWithActionKind: CodeActionKind = CodeActionKind("refactor.surround")

/**
 * The *Surround With* of the IDE as one code action, offered wherever there is something to surround. Its command is a
 * [ModChooseAction] of what to surround, then a [ModChooseAction] of the surrounders applicable to it, so the client
 * shows the two menus (see `actionChooser`). A selection is what to surround, so then the first menu is skipped, as it
 * is when there is a single candidate.
 *
 * Only a [ModCommandSurrounder] is offered: another one edits the document of an editor, which the server does not have.
 *
 * @property isTargetAtCaret whether an element around the caret is offered as what to surround; the caret line always is.
 */
class LSSurroundWithCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val isTargetAtCaret: (PsiElement) -> Boolean = { true },
) : LSCodeActionProvider {
    override val providesOnlyKinds: Set<CodeActionKind> get() = setOf(SurroundWithActionKind)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val codeActions = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val range = params.range.toTextRange(document)
                val actionContext = ActionContext(project, psiFile, range.startOffset, selectionOf(params, range), null)
                StartSurroundWithAction(isTargetAtCaret).toModCommandFixes(actionContext).map { (name, data) ->
                    applyFixCodeAction(name, SurroundWithActionKind, data, data.stamps(virtualFile.url))
                }
            }
        }
        codeActions.forEach { emit(it) }
    }
}

/**
 * Action to start surround with.
 * The first perform chooses a target expression/statement.
 */
private class StartSurroundWithAction(private val isTargetAtCaret: (PsiElement) -> Boolean) : ModCommandAction {
    override fun getFamilyName(): @Nls String = LspServerBundle.message("command.surround.with")

    override fun getPresentation(context: ActionContext): Presentation? =
        if (targets(context).isEmpty()) null else Presentation.of(familyName)

    override fun perform(context: ActionContext): ModCommand {
        val targets = targets(context)
        if (targets.size == 1) {
            return targets.single().perform(context)
        }
        return ModCommand.chooseAction(RefactoringBundle.message("introduce.target.chooser.expressions.title"), targets)
    }

    /**
     * What may be surrounded at [context], narrowest first: the selection if there is one, as the IDE does. Without one,
     * the PSI elements around the caret within its line that pass [isTargetAtCaret], and the line itself, which is what
     * the IDE surrounds then. A candidate surrounding the same elements as a narrower one is dropped.
     */
    private fun targets(context: ActionContext): List<SurroundTarget> {
        val file = context.file
        if (!context.selection.isEmpty) {
            return listOfNotNull(SurroundTarget.create(file, SurroundScope(context.selection, hasSelection = true, insideOnly = false)))
        }

        val line = SurroundWithHandler.logicalLineContents(file.fileDocument, context.offset)
        // The caret may be right after an element, like the end of an identifier, so the leaf before it counts too.
        val leaves =
            listOfNotNull(file.findElementAt(context.offset), if (context.offset > 0) file.findElementAt(context.offset - 1) else null)
        val elementScopes = leaves
            .asSequence()
            .filter { leaf -> leaf !is PsiWhiteSpace }
            .flatMap { leaf ->
                leaf.parents(true)
                    .takeWhile { element -> line.contains(element.textRange) }
                    .filter(isTargetAtCaret)
                    .map(PsiElement::getTextRange)
            }
            .filter { range -> !range.isEmpty }
            .distinct()
            .sortedBy(TextRange::getLength)
            .map { range -> SurroundScope(range, hasSelection = true, insideOnly = true) }
            .toList()
        val scopes = elementScopes + SurroundScope(line, hasSelection = false, insideOnly = false)
        return scopes.mapNotNull { scope -> SurroundTarget.create(file, scope) }.distinctBy(SurroundTarget::surroundedRanges)
    }
}

/**
 * Where to look for the elements to surround: the [range] the user selected, the range of an element around the caret,
 * or the caret line when not [hasSelection].
 *
 * @property insideOnly whether a surrounder is dropped when its elements go beyond [range]. A descriptor such as
 *   `CustomFoldingSurroundDescriptor` extends a range to whole lines, so without that the candidate of an expression
 *   would surround its statement.
 */
private data class SurroundScope(val range: TextRange, val hasSelection: Boolean, val insideOnly: Boolean) {
    /** The surrounders applicable in this scope of [file], with the elements each of them surrounds, as the IDE finds them. */
    fun applicableSurrounders(file: PsiFile): Map<ModCommandSurrounder, Array<PsiElement>> {
        if (range.isEmpty) return emptyMap()
        val surrounders =
            SurroundWithHandler.computeSurrounders(file, intArrayOf(range.startOffset), intArrayOf(range.endOffset), hasSelection)
        return buildMap {
            for ([surrounder, elements] in surrounders) {
                if (surrounder !is ModCommandSurrounder || !surrounder.isApplicable(elements)) continue
                if (insideOnly && elements.any { element -> !range.contains(element.textRange) }) continue
                put(surrounder, elements)
            }
        }
    }
}

/**
 * What to surround, found in [scope]. Performing it asks how.
 *
 * Holds no PSI: the elements are looked up again in the file of the context when a surrounder is performed.
 */
private class SurroundTarget(
    private val scope: SurroundScope,
    val surroundedRanges: Set<TextRange>,
    private val text: @NlsSafe String,
) : ModCommandAction {
    override fun getFamilyName(): @Nls String = text

    override fun getPresentation(context: ActionContext): Presentation = Presentation.of(text)

    override fun perform(context: ActionContext): ModCommand {
        val surroundActions = scope.applicableSurrounders(context.file).keys.map { surrounder -> SurroundAction(scope, surrounder) }
        return ModCommand.chooseAction(CodeInsightBundle.message("surround.with.chooser.title"), surroundActions)
    }

    companion object {
        /** The target of [scope], or `null` if no surrounder applies in it. */
        fun create(file: PsiFile, scope: SurroundScope): SurroundTarget? {
            val surrounders = scope.applicableSurrounders(file)
            if (surrounders.isEmpty()) return null
            val surroundedRanges = surrounders.values.flatMapTo(mutableSetOf()) { elements -> elements.map(PsiElement::getTextRange) }
            val surroundedText = file.fileDocument.getText(
                TextRange(
                    surroundedRanges.minOf(TextRange::getStartOffset),
                    surroundedRanges.maxOf(TextRange::getEndOffset),
                )
            )
            val text = StringUtil.shortenTextWithEllipsis(surroundedText.replace(WHITESPACE, " "), MAX_TEXT_LENGTH, 0)
            return SurroundTarget(scope, surroundedRanges, text)
        }

        private val WHITESPACE: Regex = Regex("\\s+")
        private const val MAX_TEXT_LENGTH: Int = 50
    }
}

/** Surrounds the elements found in [scope] with [surrounder]. */
private class SurroundAction(
    private val scope: SurroundScope,
    private val surrounder: ModCommandSurrounder,
) : ModCommandAction {
    override fun getFamilyName(): @Nls String = surrounder.templateDescription

    override fun getPresentation(context: ActionContext): Presentation = Presentation.of(familyName)

    override fun perform(context: ActionContext): ModCommand {
        // Not by identity: a descriptor may create its surrounders anew on each call, as CustomFoldingSurroundDescriptor does.
        val sameSurrounder = scope.applicableSurrounders(context.file).entries.firstOrNull { entry ->
            entry.key.javaClass == surrounder.javaClass && entry.key.templateDescription == surrounder.templateDescription
        } ?: return ModCommand.nop()
        return sameSurrounder.key.surroundElements(context, sameSurrounder.value)
    }
}
