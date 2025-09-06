// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.documentaiton

/**
 * Formats a given documentation comment string by cleaning up any extra spaces
 * and ensuring proper alignment for each line. Java/Kotlin-style comments are
 * expected.
 */
fun formatDocComment(docComment: String): String {
    val trimmedLines = docComment.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    return trimmedLines.joinToString(separator = "\n") { if (it.startsWith("/*")) it else " $it" }
}