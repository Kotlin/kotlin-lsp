// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.BlacklistEntry

internal val kotlinInspectionBlacklist = Blacklist(
    // Local inspections
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveRedundantQualifierNameInspection",
        reason = "slow",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection",
        reason = "slow",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.UnusedVariableInspection",
        reason = "slow",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection",
        reason = "slow",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveExplicitTypeArgumentsInspection",
        reason = "slow",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.K2MemberVisibilityCanBePrivateInspection",
        reason = "slow, performs find usages",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.VariableNeverReadInspection",
        reason = "very slow, uses extended checkers",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection",
        reason = "very slow, uses extended checkers",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PublicApiImplicitTypeInspection",
        reason = "too noisy https://github.com/Kotlin/kotlin-lsp/issues/4",
    ),
    BlacklistEntry.SuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase",
        reason = "they are slow as calling additional diagnostic collection",
    ),
    BlacklistEntry.SuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase",
        reason = "they are slow as calling additional diagnostic collection",
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
