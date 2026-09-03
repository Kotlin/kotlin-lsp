// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.BlacklistEntry

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
    BlacklistEntry.Class(
        fqcn = "org.jetbrains.kotlin.idea.quickfix.RenameIdentifierFix",
        reason = "LSP-1767",
    ),
)
