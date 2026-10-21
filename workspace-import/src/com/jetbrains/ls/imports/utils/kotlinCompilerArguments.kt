// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.idea.workspaceModel.CompilerArgumentsSerializer

/**
 * Serializes only the arguments that differ from the defaults of the arguments class, so workspace JSON dumps do not
 * churn whenever the Kotlin compiler gains new flags.
 * [org.jetbrains.kotlin.idea.workspaceModel.CompilerArgumentsSerializer.deserializeFromString] restores omitted fields
 * to defaults, as it already does for the compact strings produced by the Gradle and Maven importers.
 */
internal fun serializeNonDefaultCompilerArguments(arguments: CommonCompilerArguments): String {
    val serialized = CompilerArgumentsSerializer.serializeToString(arguments)!!
    val defaultArguments = arguments.javaClass.getDeclaredConstructor().newInstance()
    val serializedDefaults = CompilerArgumentsSerializer.serializeToString(defaultArguments)!!
    val allFields = Json.parseToJsonElement(serialized.drop(1)).jsonObject
    val defaultFields = Json.parseToJsonElement(serializedDefaults.drop(1)).jsonObject
    val nonDefaultFields = buildJsonObject {
        (allFields.keys + defaultFields.keys).forEach { key ->
            val value = allFields[key]
            if (value != defaultFields[key]) put(key, value ?: JsonNull)
        }
    }
    return serialized.take(1) + nonDefaultFields
}
