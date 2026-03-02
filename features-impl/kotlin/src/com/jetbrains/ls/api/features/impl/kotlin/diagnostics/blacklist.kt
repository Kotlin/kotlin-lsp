// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.BlacklistEntry

internal val kotlinInspectionBlacklist = Blacklist(
    // Local inspections
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveRedundantQualifierNameInspection",
        reason = "LSP-703",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection",
        reason = "LSP-704",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.UnusedVariableInspection",
        reason = "LSP-705",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection",
        reason = "LSP-706",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveExplicitTypeArgumentsInspection",
        reason = "LSP-707",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.K2MemberVisibilityCanBePrivateInspection",
        reason = "LSP-708",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.VariableNeverReadInspection",
        reason = "LSP-709",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection",
        reason = "LSP-710",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PublicApiImplicitTypeInspection",
        reason = "LSP-711",
    ),
    BlacklistEntry.SuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase",
        reason = "LSP-712",
    ),
    BlacklistEntry.SuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase",
        reason = "LSP-713",
    ),
)

internal val kotlinQuickFixBlacklist = Blacklist(
    BlacklistEntry.Class(
        fqcn = $$"org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PackageDirectoryMismatchInspection$MoveFileToPackageFix",
        reason = "LSP-582",
    ),
    BlacklistEntry.Class(
        fqcn = $$"org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PackageDirectoryMismatchInspection$ChangePackageFix",
        reason = "LSP-583",
    ),
)
