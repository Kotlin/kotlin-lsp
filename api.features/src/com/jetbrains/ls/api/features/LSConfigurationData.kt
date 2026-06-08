// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.jetbrains.ls.snapshot.api.impl.core.InitConfigurationKey
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private val json = Json { ignoreUnknownKeys = true }

fun LSConfigurationData.Companion.decode(
    initializationOptions: JsonElement?,
    entries: List<InitConfigurationEntry<*>>,
): LSConfigurationData {
    if (entries.isEmpty()) {
        return EMPTY
    }
    val obj = initializationOptions as? JsonObject ?: return EMPTY
    val values = mutableMapOf<InitConfigurationKey<*>, Any>()
    for (entry in entries) {
        val slice = obj[entry.key.id] ?: continue
        if (slice is JsonNull) {
            continue
        }
        val decoded = runCatching { json.decodeFromJsonElement(entry.serializer, slice) }
            .getOrNull() ?: continue
        values[entry.key] = decoded
    }
    return if (values.isEmpty()) {
        EMPTY
    } else {
        LSConfigurationData(values)
    }
}
