// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.DynamicBundle
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.LspServerBundle"

@ApiStatus.Internal
object LspServerBundle {
  private val instance = DynamicBundle(LspServerBundle::class.java, BUNDLE)

  fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): @Nls String =
    instance.getMessage(key, *params)

  fun messagePointer(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): Supplier<@Nls String> =
    instance.getLazyMessage(key, *params)
}
