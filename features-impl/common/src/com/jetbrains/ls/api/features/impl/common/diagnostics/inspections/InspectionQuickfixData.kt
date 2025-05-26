// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.QuickFix
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import kotlinx.serialization.Serializable

@Serializable
internal data class InspectionQuickfixData(
    val inspectionClass: String,
    /**
     * An element on which the [LocalInspectionTool] took as a root to report a quickfix.
     * It may or may not be the element on which the quickfix was really reported
     */
    val basePsiElement: PsiSerializablePointer,
    val name: String,
    val familyName: String,
    val fixClass: String,
) {
    fun matches(fix: QuickFix<*>): Boolean {
        return fixClass == fix::class.java.name
                && name == fix.name
                && familyName == fix.familyName
    }

    companion object {
        context(LSAnalysisContext, LSServer)
        fun createByFix(
            fix: QuickFix<*>,
            inspectionTool: LocalInspectionTool,
            basePsiElement: PsiElement,
            file: VirtualFile,
        ): InspectionQuickfixData {
            return InspectionQuickfixData(
                inspectionClass = inspectionTool::class.java.name,
                basePsiElement = PsiSerializablePointer.create(basePsiElement, file),
                name = fix.name,
                familyName = fix.familyName,
                fixClass = fix::class.java.name,
            )
        }
    }
}