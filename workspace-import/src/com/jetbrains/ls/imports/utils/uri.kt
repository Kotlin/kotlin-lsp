// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.api.core.util.UriConverter
import java.nio.file.Path
import kotlin.io.path.absolutePathString

internal fun String.toIntellijUri(virtualFileUrlManager: VirtualFileUrlManager): VirtualFileUrl =
    virtualFileUrlManager.getOrCreateFromUrl(asIntelliJUriString())

internal fun Path.toIntellijUri(virtualFileUrlManager: VirtualFileUrlManager): VirtualFileUrl =
    absolutePathString().toIntellijUri(virtualFileUrlManager)

private fun String.asIntelliJUriString(): String {
    val scheme = substringBefore("://", missingDelimiterValue = "file")
    val path = substringAfter("://")
    val uri = "$scheme://$path"
    val lspUri = UriConverter.intellijUriToLspUri(uri)
    return UriConverter.lspUriToIntellijUri(lspUri)
}

