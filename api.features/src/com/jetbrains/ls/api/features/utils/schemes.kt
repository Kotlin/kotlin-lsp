// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.URI

fun TextDocumentIdentifier.isSource(): Boolean {
    return uri.uri.isSource()
}

fun URI.isSource(): Boolean {
    return scheme == URI.Schemas.FILE
}