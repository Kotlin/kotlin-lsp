// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion


private const val MAX_INT_DIGITS_COUNT = Int.MAX_VALUE.toString().length

/**
 * According to the LSP spec, completion items are sorted by `sortText` field with string comparison.
 *
 * As items are already sorted by kotlin completion, we just generate string which will be sorted the same way
 */
fun getSortedFieldByIndex(index: Int): String =
    index.toString().padStart(MAX_INT_DIGITS_COUNT, '0')