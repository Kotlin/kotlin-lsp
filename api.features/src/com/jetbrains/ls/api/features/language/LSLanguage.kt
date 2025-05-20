package com.jetbrains.ls.api.features.language

import com.intellij.lang.Language
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.URI

class LSLanguage(
    /**
     * Should be unique
     */
    val lspName: String,
    val intellijLanguage: Language,
    extensions: Collection<String>,
) {
    val extensions: Set<String> = extensions.map { it.removePrefix(".") }.toSet()
    
    override fun toString(): String = lspName
    override fun equals(other: Any?): Boolean = other is LSLanguage && other.lspName == lspName
    override fun hashCode(): Int = lspName.hashCode()
}

fun LSLanguage.matches(uri: TextDocumentIdentifier): Boolean {
    return matches(uri.uri)
}

fun LSLanguage.matches(uri: DocumentUri): Boolean {
    return matches(uri.uri)
}

fun LSLanguage.matches(uri: URI): Boolean {
    return uri.fileExtension in extensions
}