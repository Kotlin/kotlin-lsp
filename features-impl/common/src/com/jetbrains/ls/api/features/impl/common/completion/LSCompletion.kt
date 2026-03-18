// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.jetbrains.lsp.protocol.CompletionParams

class LSCompletion(val params: CompletionParams, val lookup: LookupElement, val itemMatcher: PrefixMatcher)