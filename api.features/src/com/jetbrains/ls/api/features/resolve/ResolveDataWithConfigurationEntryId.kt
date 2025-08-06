// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.resolve

import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface ResolveDataWithConfigurationEntryId {
    val configurationEntryId: LSUniqueConfigurationEntry.UniqueId
}

fun getConfigurationEntryId(data: JsonElement?): LSUniqueConfigurationEntry.UniqueId? {
    if (data !is JsonObject) return null
    val idElement = data[ResolveDataWithConfigurationEntryId::configurationEntryId.name] as? JsonPrimitive ?: return null
    return LSUniqueConfigurationEntry.UniqueId(idElement.content)
}