// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.util.text.StringUtil

fun String.maybeStripHtml(): String =
    if (this.startsWith("<html>")) StringUtil.stripHtml(this, true) else this

