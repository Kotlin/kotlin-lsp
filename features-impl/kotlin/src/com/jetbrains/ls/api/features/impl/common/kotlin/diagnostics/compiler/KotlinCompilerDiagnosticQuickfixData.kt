// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.codeInsight.intention.IntentionAction
import kotlinx.serialization.Serializable

@Serializable
internal data class KotlinCompilerDiagnosticQuickfixData(
    val text: String,
    val familyName: String,
    val intentionActionClass: String,
) {

    companion object {
        fun createByIntentionAction(
            intentionAction: IntentionAction,
        ): KotlinCompilerDiagnosticQuickfixData {
            return KotlinCompilerDiagnosticQuickfixData(
                text = intentionAction.text,
                familyName = intentionAction.familyName,
                intentionActionClass = intentionAction::class.java.name,
            )
        }
    }
}