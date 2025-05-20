package com.jetbrains.ls.api.features.decompiler

import kotlinx.serialization.Serializable

@Serializable
data class DecompilerResponse(val code: String, val language: String)