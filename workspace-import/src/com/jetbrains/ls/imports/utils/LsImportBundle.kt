// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier


internal object LsImportBundle {
    private const val PATH_TO_BUNDLE = "messages.LsImport"

    val instance = DynamicBundle(LsImportBundle::class.java, PATH_TO_BUNDLE)

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = PATH_TO_BUNDLE) key: String, vararg params: Any): String = instance.getMessage(key, *params)

    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = PATH_TO_BUNDLE) key: String, vararg params: Any): Supplier<@Nls String> {
        return instance.getLazyMessage(key, *params)
    }
}
