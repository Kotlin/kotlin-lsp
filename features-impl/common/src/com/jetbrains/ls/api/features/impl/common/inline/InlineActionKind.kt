// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.inline

import com.jetbrains.lsp.protocol.CodeActionKind

object InlineActionKind {
    /**
     * Action kind to inline variable via a hot key
     */
    val RefactorInlineVariable: CodeActionKind = CodeActionKind("refactor.inline.variable")
}