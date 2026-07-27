// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.intellij.openapi.util.NlsContext
import org.jetbrains.annotations.Nls
import kotlin.annotation.AnnotationTarget.TYPE

@NlsContext(prefix = "command")
@Target(TYPE)
@Nls(capitalization = Nls.Capitalization.Sentence)
annotation class LspCommand

class LSCommandDescriptor(
    val title: @LspCommand String,
    val name: String,
    val executor: LSCommandExecutor
)