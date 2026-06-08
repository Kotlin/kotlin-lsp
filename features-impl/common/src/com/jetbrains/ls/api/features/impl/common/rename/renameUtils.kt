// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.jetbrains.ls.api.core.util.fileExtension
import com.jetbrains.ls.api.core.util.fileName
import com.jetbrains.ls.api.core.util.scheme
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import com.jetbrains.lsp.protocol.URI

/**
 * Calculates the difference in names between two URIs.
 * @see [NameChange]
 */
fun computeNameChange(old: URI, new: URI, isDirectory: Boolean): NameChange? {
    if (old.scheme != new.scheme) return null
    val oldFileUrl = old.toFileUrl() ?: return null
    val newFileUrl = new.toFileUrl() ?: return null
    if (oldFileUrl.parent != newFileUrl.parent) return null

    return if (isDirectory) computeDirectoryNameChange(old, new) else computeFileNameChange(old, new)
}

private fun computeDirectoryNameChange(old: URI, new: URI): NameChange? {
    val newExtension = new.fileExtension
    val oldExtension = old.fileExtension

    if (oldExtension != null || newExtension != null) return null

    val oldName = old.fileName
    val newName = new.fileName
    if (oldName == newName) return null

    return NameChange(
        oldName,
        newName
    )
}

private fun computeFileNameChange(old: URI, new: URI): NameChange? {
    val newExtension = new.fileExtension
    val oldExtension = old.fileExtension

    if (oldExtension == null || newExtension == null || newExtension != oldExtension) return null


    val oldName = old.fileName
    val newName = new.fileName
    if (oldName == newName) return null
    return NameChange(
        oldName.getPureName(oldExtension),
        newName.getPureName(newExtension)
    )
}

private fun String.getPureName(extension: String) = removeSuffix(extension).trimEnd { it == '.' }

/**
 * Represents the difference in the name when `workspace/willRenameFiles` request occurs.
 */
class NameChange(
    val oldName: String,
    val newName: String
)
