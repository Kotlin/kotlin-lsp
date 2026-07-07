// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.intentions

import com.intellij.modcommand.ModCommandAction
import org.jetbrains.kotlin.idea.codeinsight.intentions.SpecifyTypeExplicitlyIntention

fun kotlinIntentionConverter() : (ModCommandAction) -> ModCommandAction = {
    when (it) {
        is SpecifyTypeExplicitlyIntention -> SpecifyTypeExplicitlyIntention(false)
        else -> it
    }
}