// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.jetbrains.analyzer.api.FileTypeId
import com.jetbrains.ls.api.core.LanguageId
import com.jetbrains.ls.api.core.util.fileExtension
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.URI

class LSLanguage(
    /**
     * Should be unique
     */
    val lspName: String,
    /** A static instance, so its language and its id need no registry. */
    fileType: LanguageFileType,
    extensions: Collection<String>,
) {
    val extensions: Set<String> = extensions.map { it.removePrefix(".") }.toSet()

    val intellijLanguage: Language = fileType.language

    /** The file type that retypes an open document of this language for the analyzer. */
    val fileTypeId: FileTypeId = FileTypeId(fileType.name)

    val languageId: LanguageId = LanguageId(lspName)
    
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