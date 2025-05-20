//package com.jetbrains.ls.api.features.impl.common.diagnostics.intentions
//
//import com.intellij.codeIntention.LocalIntentionTool
//import com.intellij.codeIntention.QuickFix
//import com.intellij.openapi.vfs.VirtualFile
//import com.intellij.psi.PsiElement
//import com.jetbrains.ls.api.core.LSAnalysisContext
//import com.jetbrains.ls.api.core.LSServer
//import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
//import kotlinx.serialization.Serializable
//
//@Serializable
//internal data class IntentionQuickfixData(
//    val intentionClass: String,
//    /**
//     * An element on which the [LocalIntentionTool] took as a root to report a quickfix.
//     * It may or may not be the element on which the quickfix was really reported
//     */
//    val basePsiElement: PsiSerializablePointer,
//    val name: String,
//    val familyName: String,
//    val fixClass: String,
//) {
//    fun matches(fix: QuickFix<*>): Boolean {
//        return fixClass == fix::class.java.name
//                && name == fix.name
//                && familyName == fix.familyName
//    }
//
//    companion object {
//        context(LSAnalysisContext, LSServer)
//        fun createByFix(
//            fix: QuickFix<*>,
//            intentionTool: LocalIntentionTool,
//            basePsiElement: PsiElement,
//            file: VirtualFile,
//        ): IntentionQuickfixData {
//            return IntentionQuickfixData(
//                intentionClass = intentionTool::class.java.name,
//                basePsiElement = PsiSerializablePointer.create(basePsiElement, file),
//                name = fix.name,
//                familyName = fix.familyName,
//                fixClass = fix::class.java.name,
//            )
//        }
//    }
//}