// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion.rekot

internal const val COMPLETION_FAKE_IDENTIFIER = "RWwgUHN5IEtvbmdyb28g"

internal fun matchesPrefix(prefix: String?, item: String): Boolean {
    if (prefix == null) return true
    return item.contains(prefix, ignoreCase = true)
}
