// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.completion.api.serialization.lookup.model.LookupElementModel

@Serializable
@ApiStatus.Internal
data class KotlinCompletionLookupItemData(
  val prefix: String,
  val model: LookupElementModel,
)