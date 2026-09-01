// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.BlacklistEntry
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher

internal val kotlinInspectionPatcher = InspectionProfilePatcher(
    // Local inspections
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.RemoveRedundantQualifierNameInspection",
        reason = "LSP-703",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.KotlinUnusedImportInspection",
        reason = "LSP-704",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.UnusedVariableInspection",
        reason = "LSP-705",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection",
        reason = "LSP-706",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.RemoveExplicitTypeArgumentsInspection",
        reason = "LSP-707",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.K2MemberVisibilityCanBePrivateInspection",
        reason = "LSP-708",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.VariableNeverReadInspection",
        reason = "LSP-709",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection",
        reason = "LSP-710",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.PublicApiImplicitTypeInspection",
        reason = "LSP-711",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.UnusedSymbolInspection",
        reason = "LSP-1005",
    ),
    InspectionProfilePatcher.Patch.DisableSuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase",
        reason = "LSP-712",
    ),
    InspectionProfilePatcher.Patch.DisableSuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase",
        reason = "LSP-713",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "com.intellij.codeInspection.test.TestFailedLineInspection",
        // Needs TestStateStorage (persistent test-run history), which is meaningless in a headless
        // language server and collides ("storage already registered") across analysis contexts.
        reason = "LSP-1507; requires TestStateStorage; irrelevant and storage-conflicting in the language server",
    )
)

internal val kotlinIntentionBlacklist = Blacklist(
    // no blacklisted intentions
)

internal val kotlinQuickFixBlacklist = Blacklist(
    BlacklistEntry.Class(
        fqcn = $$"org.jetbrains.kotlin.idea.codeInsight.inspections.PackageDirectoryMismatchInspection$MoveFileToPackageFix",
        reason = "LSP-582",
    ),
    BlacklistEntry.Class(
        fqcn = $$"org.jetbrains.kotlin.idea.codeInsight.inspections.PackageDirectoryMismatchInspection$ChangePackageFix",
        reason = "LSP-583",
    ),
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.highlighting.SafeDeleteFix",
        reason = "LSP-970",
    ),
)
