// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import kotlinx.serialization.Serializable

@Serializable
internal data class InspectionQuickfixData(
    val name: String,
    val modCommandData: ModCommandData,
)