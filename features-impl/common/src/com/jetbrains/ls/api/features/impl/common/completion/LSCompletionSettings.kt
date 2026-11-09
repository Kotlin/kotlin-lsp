// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.Application
import com.intellij.openapi.components.serviceOrNull
import com.jetbrains.analyzer.api.AnalyzerContextKind
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.ls.snapshot.api.impl.core.InitConfigurationKey
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.SessionComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The completion settings of a session. The client sends them as an object in the `completion` key of `initializationOptions`.
 * A missing field gives its default. A field that the server cannot decode makes the server use the defaults for all fields.
 */
@Serializable
data class LSCompletionSettings(
    /** How completion compares the case of the typed prefix with the case of an item. */
    val caseSensitivity: LSCompletionCaseSensitivity = LSCompletionCaseSensitivity.FIRST_LETTER,
) {
    /** Writes the settings into the IDE settings that the completion code reads. */
    internal fun applyTo(application: Application) {
        application.serviceOrNull<CodeInsightSettings>()?.completionCaseSensitive = caseSensitivity.codeInsightValue
    }
}

/** The "Match case" option of the IDE. */
@Serializable
enum class LSCompletionCaseSensitivity(internal val codeInsightValue: Int) {
    /** The case is ignored, so `string` gives `String`. */
    @SerialName("none")
    NONE(CodeInsightSettings.NONE),

    /** Only the first letter must have the same case. This is the default of the IDE. */
    @SerialName("firstLetter")
    FIRST_LETTER(CodeInsightSettings.FIRST_LETTER),

    /** All letters must have the same case. */
    @SerialName("all")
    ALL(CodeInsightSettings.ALL),
}

val CompletionSettingsKey: InitConfigurationKey<LSCompletionSettings> = InitConfigurationKey(id = "completion")

/** Applies the [LSCompletionSettings] of the session to each analyzer context of the session. */
object LSCompletionSettingsSessionComponent : SessionComponent<LSCompletionSettings> {
    override fun init(configData: LSConfigurationData): LSCompletionSettings =
        configData[CompletionSettingsKey] ?: LSCompletionSettings()

    override suspend fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: LSCompletionSettings,
        contextKind: AnalyzerContextKind,
    ) {
        state.applyTo(application)
    }
}
