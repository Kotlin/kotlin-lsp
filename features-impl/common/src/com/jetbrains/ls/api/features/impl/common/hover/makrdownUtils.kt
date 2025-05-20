package com.jetbrains.ls.api.features.impl.common.hover

fun markdownMultilineCode(code: String, language: String?): String {
    return buildString {
        // quadruple quotes to be able to have backticks inside the code
        appendLine("````${language.orEmpty()}")
        appendLine(code)
        appendLine("````")
    }
}