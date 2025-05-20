package com.jetbrains.ls.api.features

import com.jetbrains.ls.api.features.language.LSLanguage

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}


fun LSLanguageSpecificConfigurationEntry.supportLanguage(language: LSLanguage): Boolean =
    supportedLanguages.contains(language)