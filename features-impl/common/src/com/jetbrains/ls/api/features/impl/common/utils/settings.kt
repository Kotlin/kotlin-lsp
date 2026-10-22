// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads XML file by given [path]
 * @return null, if path doesn't exist or load failed, [Element] otherwise
 */
internal fun loadXml(path: Path): Element? {
    if (Files.notExists(path)) return null
    return try {
        JDOMUtil.load(path)
    }
    catch (e: Exception) {
        rethrowControlFlowException(e)
        null
    }
}