// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.configuration

import com.jetbrains.ls.api.features.LSConfigurationEntry
import kotlinx.serialization.Serializable

interface LSUniqueConfigurationEntry : LSConfigurationEntry  {
    val uniqueId: UniqueId

    @Serializable
    @JvmInline
    value class UniqueId(val value: String)
}