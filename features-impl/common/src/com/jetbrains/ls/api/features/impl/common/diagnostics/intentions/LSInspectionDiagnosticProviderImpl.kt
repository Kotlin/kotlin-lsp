//package com.jetbrains.ls.api.features.impl.common.diagnostics.intentions
//
//import com.intellij.codeInsight.intention.impl.config.IntentionActionWrapper
//import com.intellij.codeInsight.intention.impl.config.IntentionManagerImpl.EP_INTENTION_ACTIONS
//import com.intellij.codeIntention.*
//import com.intellij.codeIntention.ex.IntentionManagerEx
//import com.intellij.lang.Language
//import com.intellij.openapi.diagnostic.getOrLogException
//import com.intellij.openapi.diagnostic.logger
//import com.intellij.openapi.util.TextRange
//import com.intellij.openapi.vfs.VirtualFile
//import com.intellij.openapi.vfs.findDocument
//import com.intellij.openapi.vfs.findPsiFile
//import com.intellij.psi.PsiElement
//import com.intellij.psi.PsiElementVisitor
//import com.intellij.psi.PsiFile
//import com.jetbrains.ls.api.core.util.findVirtualFile
//import com.jetbrains.ls.api.core.util.toLspRange
//import com.jetbrains.ls.api.core.LSAnalysisContext
//import com.jetbrains.ls.api.core.LSServer
//import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
//import com.jetbrains.ls.api.features.language.LSLanguage
//import com.jetbrains.lsp.protocol.*
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.flow
//import kotlinx.serialization.json.encodeToJsonElement
//import kotlin.reflect.KClass
//
//class LSIntentionDiagnosticProviderImpl(
//    override val supportedLanguages: Set<LSLanguage>,
//): LSDiagnosticProvider {
//    context(LSServer)
//    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
//        val onTheFly = false
//        withAnalysisContext(params.textDocument.uri.uri) c@{
//            val file = params.textDocument.findVirtualFile() ?: return@c emptyList()
//            val psiFile = file.findPsiFile(project) ?: return@c emptyList()
//            val intentions = getIntentions(psiFile.language).ifEmpty { return@c emptyList() }
//            val visitors = intentions.map { intention ->
//                intention.isApplicable()
//                IntentionWithVisitor(intention, intention.buildVisitor(holder, onTheFly))
//            }
//
//            collectDiagnostics(holder, psiFile, file, visitors)
//        }.forEach { emit(it) }
//    }
//
//
//
//    // kotlin ones should be moved closer to the kotlin impl
//    private val blacklist = BlackList(
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.RemoveRedundantQualifierNameIntention", "slow"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.codeInsight.intentions.shared.KotlinUnusedImportIntention", "slow"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.diagnosticBased.UnusedVariableIntention", "slow"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.diagnosticBased.KotlinUnreachableCodeIntention", "slow"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.RemoveExplicitTypeArgumentsIntention", "slow"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.K2MemberVisibilityCanBePrivateIntention", "slow, performs find usages"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.diagnosticBased.VariableNeverReadIntention", "very slow, uses extended checkers"),
//        BlackListEntry.IntentionClass("org.jetbrains.kotlin.idea.k2.codeinsight.intentions.diagnosticBased.AssignedValueIsNeverReadIntention", "very slow, uses extended checkers"),
//        BlackListEntry.IntentionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinKtDiagnosticBasedIntentionBase", "they are slow as calling additional diagnostic collection"),
//        BlackListEntry.IntentionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiDiagnosticBasedIntentionBase", "they are slow as calling additional diagnostic collection"),
//    )
//
//    private class BlackList(
//        entries: List<BlackListEntry>,
//    ) {
//        constructor(vararg entries: BlackListEntry) : this(entries.toList())
//
//        private val implementationClasses = entries.mapTo(mutableSetOf()) { (it as? BlackListEntry.IntentionClass)?.intentionClass }
//        private val superClasses = entries.mapTo(mutableSetOf()) { (it as? BlackListEntry.IntentionSuperClass)?.superClass }
//
//        fun containsImplementation(intentionClass: String): Boolean {
//            return intentionClass in implementationClasses
//        }
//
//        fun containsSuperClass(intentionInstance: Any): Boolean {
//            return intentionInstance::class.supertypes.any { (it.classifier as? KClass<*>)?.java?.name in superClasses }
//        }
//    }
//
//    private sealed class BlackListEntry{
//        abstract val reason: String
//
//        protected fun ensureReason() {
//            require(reason.isNotBlank()) { "intentionClass must not be blank" }
//        }
//
//        data class IntentionClass(val intentionClass: String, override val reason: String): BlackListEntry() {
//            init {
//                ensureReason()
//            }
//        }
//        data class IntentionSuperClass(val superClass: String, override val reason: String): BlackListEntry() {
//            init {
//                ensureReason()
//            }
//        }
//    }
//
//    private fun getIntentions(language: Language): List<IntentionActionWrapper> {
//        return EP_INTENTION_ACTIONS.extensionList
//            .filter { it.language == language.id }
//            .map { IntentionActionWrapper(it) }
//            //.filterNot { blacklist.containsImplementation(it.implementationClass) }
//            //.filterNot { blacklist.containsSuperClass(it) }
//    }
//
//    context(LSAnalysisContext, LSServer)
//    private fun collectDiagnostics(
//        holder: ProblemsHolder,
//        psiFile: PsiFile,
//        file: VirtualFile,
//        visitors: List<IntentionWithVisitor>,
//    ): List<Diagnostic> {
//        val results = mutableListOf<Diagnostic>()
//
//        fun collect(element: PsiElement) {
//            for ((intention, visitor) in visitors) {
//                runCatching {
//                    element.accept(visitor)
//                }.getOrLogException { LOG.warn(it) }
//                if (holder.hasResults()) {
//                    results += holder.collectDiagnostics(file, intention, element)
//                }
//                holder.clearResults()
//            }
//        }
//
//        psiFile.accept(object : PsiElementVisitor() {
//            override fun visitElement(element: PsiElement) {
//                collect(element)
//                element.acceptChildren(this)
//            }
//        })
//
//        return results
//    }
//
//    private data class IntentionWithVisitor(
//        val intention: LocalIntentionTool,
//        val visitor: PsiElementVisitor,
//    )
//
//    context(LSAnalysisContext, LSServer)
//    private fun ProblemsHolder.collectDiagnostics(
//        file: VirtualFile,
//        localIntentionTool: LocalIntentionTool,
//        element: PsiElement
//    ): List<Diagnostic> {
//        val document = file.findDocument() ?: return emptyList()
//        return results.mapNotNull { result ->
//            val data = result.createDiagnosticData(localIntentionTool, element, file)
//            Diagnostic(
//                range = result.range()?.toLspRange(document) ?: return@mapNotNull null,
//                severity = result.highlightType.toLsp(),
//                // todo handle markers from [com.intellij.codeIntention.CommonProblemDescriptor.getDescriptionTemplate]
//                message = result.tooltipTemplate,
//                code = StringOrInt.string(localIntentionTool.id),
//                tags = result.highlightType.toLspTags(),
//                data = LSP.json.encodeToJsonElement(data),
//            )
//        }
//    }
//
//    context(LSAnalysisContext, LSServer)
//    private fun ProblemDescriptor.range(): TextRange? {
//        val element = psiElement ?: return null
//        val elementRange = element.textRange ?: return null
//        // relative range -> absolute range
//        textRangeInElement?.let { return it.shiftRight(elementRange.startOffset) }
//        return elementRange
//    }
//
//
//    context(LSAnalysisContext, LSServer)
//    private fun ProblemDescriptor.createDiagnosticData(
//        localIntentionTool: LocalIntentionTool,
//        element: PsiElement,
//        file: VirtualFile
//    ): IntentionDiagnosticData? {
//        return IntentionDiagnosticData(
//            fixes = fixes.orEmpty().map { fix ->
//                IntentionQuickfixData.createByFix(fix, localIntentionTool, element, file)
//            }
//        )
//    }
//}
//
//private val LOG = logger<LSIntentionDiagnosticProviderImpl>()
//
//
//// TODO design, currently some random conversions
//private fun ProblemHighlightType.toLsp(): DiagnosticSeverity = when (this) {
//    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> DiagnosticSeverity.Hint
//    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> DiagnosticSeverity.Error
//    ProblemHighlightType.LIKE_DEPRECATED -> DiagnosticSeverity.Warning
//    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> DiagnosticSeverity.Warning
//    ProblemHighlightType.ERROR -> DiagnosticSeverity.Error
//    ProblemHighlightType.WARNING -> DiagnosticSeverity.Warning
//    ProblemHighlightType.GENERIC_ERROR -> DiagnosticSeverity.Error
//    ProblemHighlightType.INFO -> DiagnosticSeverity.Information
//    ProblemHighlightType.WEAK_WARNING -> DiagnosticSeverity.Warning
//    ProblemHighlightType.INFORMATION -> DiagnosticSeverity.Hint
//    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> DiagnosticSeverity.Information
//    ProblemHighlightType.POSSIBLE_PROBLEM -> DiagnosticSeverity.Warning
//}
//
//// TODO design, currently some random conversions
//private fun ProblemHighlightType.toLspTags(): List<DiagnosticTag>? = when (this) {
//    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> null
//    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> null
//    ProblemHighlightType.LIKE_DEPRECATED -> listOf(DiagnosticTag.Deprecated)
//    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> listOf(DiagnosticTag.Unnecessary)
//    ProblemHighlightType.ERROR -> null
//    ProblemHighlightType.WARNING -> null
//    ProblemHighlightType.GENERIC_ERROR -> null
//    ProblemHighlightType.INFO -> null
//    ProblemHighlightType.WEAK_WARNING -> null
//    ProblemHighlightType.INFORMATION -> null
//    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> listOf(DiagnosticTag.Deprecated)
//    ProblemHighlightType.POSSIBLE_PROBLEM -> null
//}