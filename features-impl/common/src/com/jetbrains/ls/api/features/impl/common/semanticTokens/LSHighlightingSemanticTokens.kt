// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.semanticTokens

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl
import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtilBase
import com.intellij.util.ReflectionUtil
import com.jetbrains.ls.api.core.features.LSInjectedFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.semanticTokens.LSInjectedSemanticTokensFallback
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticToken
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenType
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenTypePredefined
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenWithRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

private val LOG = logger<LSHighlightingSemanticTokens>()

/**
 * Generic semantic tokens of an injected file whose language has no LS semantic tokens provider. Two layers:
 * - lexer: the injected language's [SyntaxHighlighterFactory] highlighter over the injected text with host escapes decoded, as
 *   the injected PSI was parsed (the [LSInjectedFile.documentWindow] text keeps them: `\\|` of a Java literal is one escaped `|`);
 * - annotators: [LanguageAnnotators] of the injected language, whose tokens override the lexer tokens they overlap.
 *
 * A [TextAttributesKey] maps to a token type by walking its fallback keys to a [DefaultLanguageHighlighterColors] key of
 * [tokenTypeByDefaultKey]; other keys yield no token. The result does not overlap.
 */
object LSHighlightingSemanticTokens : LSInjectedSemanticTokensFallback {
    override fun tokens(injected: LSInjectedFile, job: Job): List<LSSemanticTokenWithRange> {
        val psiFile = injected.psiFile
        val annotatorPieces = annotatorPieces(psiFile, job).sortedBy { it.range.length }
        val lexerPieces = lexerPieces(psiFile, job)
        return layer(listOf(annotatorPieces, lexerPieces), job).map { piece ->
            LSSemanticTokenWithRange(LSSemanticToken(piece.type), piece.range.toLspRange(injected.documentWindow))
        }
    }

    private class Piece(val range: TextRange, val type: LSSemanticTokenType)

    /**
     * The text the injected PSI was parsed from: prefixes, suffixes and host text with host escapes decoded, like
     * `InjectionRegistrarImpl` builds it. [windowOffsets] maps each offset of [text], end included, to the window offset.
     */
    private class DecodedText(val text: CharSequence, private val windowOffsets: IntArray) {
        fun windowRange(start: Int, end: Int): TextRange = TextRange(windowOffsets[start], windowOffsets[end])
    }

    /** Null when a shred lost its host. */
    private fun decodedText(psiFile: PsiFile): DecodedText? {
        val shreds = InjectedLanguageUtilBase.getShreds(psiFile) ?: return null
        val text = StringBuilder()
        var windowOffsets = IntArray(psiFile.textLength + 1) // the window (and PSI) text is the longer one for usual escapers
        var windowOffset = 0
        fun map(textOffset: Int, toWindowOffset: Int) {
            if (textOffset >= windowOffsets.size) windowOffsets = windowOffsets.copyOf(maxOf(textOffset + 1, windowOffsets.size * 2))
            windowOffsets[textOffset] = toWindowOffset
        }
        fun appendVerbatim(chars: String) {
            for (char in chars) {
                map(text.length, windowOffset++)
                text.append(char)
            }
        }
        for (shred in shreds) {
            val host = shred.host ?: return null
            val rangeInsideHost = shred.rangeInsideHost
            appendVerbatim(shred.prefix)
            val escaper = host.createLiteralTextEscaper()
            val decodedStart = text.length
            escaper.decode(rangeInsideHost, text) // on a bad escape, decodes a prefix, as the injected PSI does
            for (offset in decodedStart until text.length) {
                val inHost = escaper.getOffsetInHost(offset - decodedStart, rangeInsideHost)
                map(offset, windowOffset + if (inHost < 0) rangeInsideHost.length else inHost - rangeInsideHost.startOffset)
            }
            windowOffset += rangeInsideHost.length
            appendVerbatim(shred.suffix)
        }
        map(text.length, windowOffset)
        return DecodedText(text, windowOffsets)
    }

    private fun lexerPieces(psiFile: PsiFile, job: Job): List<Piece> = guarded("syntax highlighter of ${psiFile.language}") {
        val decoded = decodedText(psiFile) ?: return@guarded emptyList()
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(psiFile.language, psiFile.project, psiFile.virtualFile)
        val lexer = highlighter.highlightingLexer
        lexer.start(decoded.text)
        buildList {
            while (true) {
                job.ensureActive()
                val tokenType = lexer.tokenType ?: break
                // Later keys are layered on top of earlier ones (`SyntaxHighlighterBase.pack`), so the last mapped key wins.
                val type = highlighter.getTokenHighlights(tokenType).reversed().firstNotNullOfOrNull { tokenTypeOf(it) }
                if (type != null && lexer.tokenEnd > lexer.tokenStart) add(Piece(decoded.windowRange(lexer.tokenStart, lexer.tokenEnd), type))
                lexer.advance()
            }
        }
    }.orEmpty()

    private fun annotatorPieces(psiFile: PsiFile, job: Job): List<Piece> {
        val templates = DumbService.getInstance(psiFile.project).filterByDumbAwareness(LanguageAnnotators.INSTANCE.allForLanguageOrAny(psiFile.language))
        // A fresh instance per request, like `AnnotatorRunner` does: an annotator may keep its holder in a field (`RegExpAnnotator`).
        val annotators = templates.mapNotNull { template ->
            guarded("instantiation of annotator ${template.javaClass.name}") { ReflectionUtil.newInstance(template.javaClass) }
        }
        return annotators.flatMap { annotator ->
            job.ensureActive()
            guarded("annotator ${annotator.javaClass.name}") { annotations(psiFile, annotator, job) }.orEmpty().mapNotNull { annotation ->
                val type = tokenTypeOf(annotation.textAttributes) ?: return@mapNotNull null
                val range = TextRange(annotation.startOffset, annotation.endOffset)
                if (range.isEmpty || range.endOffset > psiFile.textLength) null else Piece(range, type)
            }
        }
    }

    private fun annotations(psiFile: PsiFile, annotator: Annotator, job: Job): List<Annotation> =
        AnnotationSessionImpl.computeWithSession(psiFile, false, annotator) { holder ->
            val annotationHolder = holder as AnnotationHolderImpl
            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    job.ensureActive()
                    annotationHolder.runAnnotatorWithContext(element)
                    super.visitElement(element)
                }
            })
            annotationHolder.toList()
        }

    /**
     * Places the pieces in order (layer by layer, then in list order), each clipped by every piece placed before it, including
     * earlier pieces of its own layer, and joins adjacent pieces of one type.
     */
    private fun layer(layers: List<List<Piece>>, job: Job): List<Piece> {
        val placed = ArrayList<Piece>() // disjoint, sorted by start
        for (piece in layers.asSequence().flatten()) {
            job.ensureActive()
            for (free in subtract(piece.range, placed)) {
                // `free` lies in a gap of `placed`, so its insertion point is where the first piece starting after it is.
                val index = placed.binarySearch { if (it.range.startOffset < free.startOffset) -1 else 1 }.let { -it - 1 }
                placed.add(index, Piece(free, piece.type))
            }
        }
        val joined = ArrayList<Piece>(placed.size)
        for (piece in placed) {
            val last = joined.lastOrNull()
            if (last != null && last.type == piece.type && last.range.endOffset == piece.range.startOffset) {
                joined[joined.lastIndex] = Piece(TextRange(last.range.startOffset, piece.range.endOffset), piece.type)
            }
            else {
                joined += piece
            }
        }
        return joined
    }

    /** The parts of [range] outside every range of [occupied] (disjoint, sorted by start). */
    private fun subtract(range: TextRange, occupied: List<Piece>): List<TextRange> {
        var first = occupied.binarySearch { if (it.range.endOffset <= range.startOffset) -1 else 1 }.let { -it - 1 }
        val result = ArrayList<TextRange>(1)
        var start = range.startOffset
        while (first < occupied.size && occupied[first].range.startOffset < range.endOffset) {
            val blocker = occupied[first].range
            if (blocker.startOffset > start) result += TextRange(start, blocker.startOffset)
            start = maxOf(start, blocker.endOffset)
            first++
        }
        if (start < range.endOffset) result += TextRange(start, range.endOffset)
        return result
    }

    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        }
        catch (e: Throwable) {
            rethrowControlFlowException(e)
            LOG.warn("Semantic tokens of an injection: $what failed", e)
            null
        }

    private val tokenTypeByDefaultKey: Map<TextAttributesKey, LSSemanticTokenType> = mapOf(
        DefaultLanguageHighlighterColors.KEYWORD to LSSemanticTokenTypePredefined.KEYWORD,
        DefaultLanguageHighlighterColors.STRING to LSSemanticTokenTypePredefined.STRING,
        DefaultLanguageHighlighterColors.NUMBER to LSSemanticTokenTypePredefined.NUMBER,
        DefaultLanguageHighlighterColors.LINE_COMMENT to LSSemanticTokenTypePredefined.COMMENT,
        DefaultLanguageHighlighterColors.BLOCK_COMMENT to LSSemanticTokenTypePredefined.COMMENT,
        DefaultLanguageHighlighterColors.DOC_COMMENT to LSSemanticTokenTypePredefined.COMMENT,
        DefaultLanguageHighlighterColors.OPERATION_SIGN to LSSemanticTokenTypePredefined.OPERATOR,
        DefaultLanguageHighlighterColors.CLASS_NAME to LSSemanticTokenTypePredefined.CLASS,
        DefaultLanguageHighlighterColors.CLASS_REFERENCE to LSSemanticTokenTypePredefined.CLASS,
        DefaultLanguageHighlighterColors.INTERFACE_NAME to LSSemanticTokenTypePredefined.INTERFACE,
        DefaultLanguageHighlighterColors.INSTANCE_FIELD to LSSemanticTokenTypePredefined.PROPERTY,
        DefaultLanguageHighlighterColors.STATIC_FIELD to LSSemanticTokenTypePredefined.PROPERTY,
        DefaultLanguageHighlighterColors.LOCAL_VARIABLE to LSSemanticTokenTypePredefined.VARIABLE,
        DefaultLanguageHighlighterColors.PARAMETER to LSSemanticTokenTypePredefined.PARAMETER,
        DefaultLanguageHighlighterColors.FUNCTION_DECLARATION to LSSemanticTokenTypePredefined.FUNCTION,
        DefaultLanguageHighlighterColors.FUNCTION_CALL to LSSemanticTokenTypePredefined.FUNCTION,
        DefaultLanguageHighlighterColors.STATIC_METHOD to LSSemanticTokenTypePredefined.METHOD,
        DefaultLanguageHighlighterColors.INSTANCE_METHOD to LSSemanticTokenTypePredefined.METHOD,
    )

    private fun tokenTypeOf(key: TextAttributesKey): LSSemanticTokenType? {
        var current: TextAttributesKey? = key
        val seen = HashSet<TextAttributesKey>()
        while (current != null && seen.add(current)) {
            tokenTypeByDefaultKey[current]?.let { return it }
            current = current.fallbackAttributeKey
        }
        return null
    }
}
