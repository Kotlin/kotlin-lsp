// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.language.LSLanguageConfiguration
import com.jetbrains.ls.api.features.language.matches
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokensProvider
import com.jetbrains.lsp.protocol.TextDocumentIdentifier

class LSConfiguration(
    val entries: List<LSConfigurationEntry>,
    val plugins: List<PluginMainDescriptor>,
    val languages: List<LSLanguage>,
) {
    val allCommandDescriptors: List<LSCommandDescriptor> = entries<LSCommandDescriptorProvider>().flatMap { it.commandDescriptors }
    val allSemanticTokensProviders: List<LSSemanticTokensProvider> = entries<LSSemanticTokensProvider>()

    init {
        allCommandDescriptors.requireNoDuplicatesBy { it.name }
        languages.requireNoDuplicatesBy { it.lspName }
        entries.filterIsInstance<LSUniqueConfigurationEntry>().requireNoDuplicatesBy { it.uniqueId }
    }

    private val descriptorByNames: Map<String, LSCommandDescriptor> = allCommandDescriptors.associateBy { it.name }


    @PublishedApi
    internal val entriesByLanguage: Map<LSLanguage, List<LSLanguageSpecificConfigurationEntry>> =
        buildMap<LSLanguage, MutableList<LSLanguageSpecificConfigurationEntry>> {
            for (entry in this@LSConfiguration.entries) {
                if (entry !is LSLanguageSpecificConfigurationEntry) continue
                for (language in entry.supportedLanguages) {
                    val entriesForLanguage = getOrPut(language) { mutableListOf() }
                    entriesForLanguage += entry
                }
            }
        }

    fun commandDescriptorByCommandName(commandName: String): LSCommandDescriptor? {
        return descriptorByNames[commandName]
    }

    inline fun <reified E : LSConfigurationEntry> entries(): List<E> {
        return entries.filterIsInstance<E>()
    }

    inline fun <reified E : LSLanguageSpecificConfigurationEntry> entriesFor(
        document: TextDocumentIdentifier,
    ): List<E> {
        val language = languageFor(document) ?: return emptyList()
        return entriesFor(language)
    }

    inline fun <reified E : LSLanguageSpecificConfigurationEntry> entriesFor(
        language: LSLanguage,
    ): List<E> {
        return entriesByLanguage[language]?.filterIsInstance<E>() ?: emptyList()
    }

    fun languageFor(document: TextDocumentIdentifier): LSLanguage? {
        return languages.firstOrNull { it.matches(document) }
    }
}

fun LSConfiguration(
    languageConfigurations: List<LSLanguageConfiguration>,
): LSConfiguration {
    return LSConfiguration(
        entries = languageConfigurations.flatMap { it.entries },
        plugins = languageConfigurations.flatMap { it.plugins },
        languages = languageConfigurations.flatMap { it.languages },
    )
}

fun LSConfiguration(
    vararg languageConfigurations: LSLanguageConfiguration,
): LSConfiguration {
    return LSConfiguration(languageConfigurations.toList())
}

private fun <E, D> Collection<E>.requireNoDuplicatesBy(keySelector: (E) -> D) {
    val duplicates = groupBy(keySelector).filterValues { it.size > 1 }
    require(duplicates.isEmpty()) {
        "Duplicates found: ${duplicates.keys.joinToString(separator = "\n") { "$it is provided by more than one entry: ${duplicates[it]}" }}"
    }
}