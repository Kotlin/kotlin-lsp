// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import org.jdom.Element
import java.nio.file.Path

/**
 * Loads the XML file at [path] through the virtual file system.
 * @return null, if path doesn't exist or load failed, [Element] otherwise
 */
internal fun loadXml(path: Path): Element? {
    return try {
        val file = VirtualFileManager.getInstance().findFileByUrl(path.toFileUrl().url) ?: return null
        file.inputStream.use { JDOMUtil.load(it) }
    } catch (e: Exception) {
        rethrowControlFlowException(e)
        null
    }
}