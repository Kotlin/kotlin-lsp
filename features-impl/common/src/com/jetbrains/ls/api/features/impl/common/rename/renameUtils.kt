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
        Name(oldName, ""),
        Name(newName, ""),
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
        oldName.getName(oldExtension),
        newName.getName(newExtension)
    )
}

private fun String.getName(extension: String): Name {
    val pureName = removeSuffix(extension).trimEnd { it == '.' }
    val suffix = substring(pureName.length)
    return Name(pureName, suffix)
}
/**
 * Represents the difference in the name when `workspace/willRenameFiles` request occurs.
 * @param oldName name of the file before the refactoring without extension
 * @param newName name of the file after the refactoring without extension
 */
class NameChange(
    val oldName: Name,
    val newName: Name
)

class Name(
    val fileName: String,
    val suffix: String
) {
    fun fullName(): String = "$fileName$suffix"
}
