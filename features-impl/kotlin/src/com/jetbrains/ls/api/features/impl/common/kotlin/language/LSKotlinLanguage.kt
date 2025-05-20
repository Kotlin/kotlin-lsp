package com.jetbrains.ls.api.features.impl.common.kotlin.language

import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.idea.KotlinLanguage

val LSKotlinLanguage: LSLanguage = LSLanguage(
    lspName = "kotlin",
    intellijLanguage = KotlinLanguage.INSTANCE,
    extensions = listOf("kt"),
)