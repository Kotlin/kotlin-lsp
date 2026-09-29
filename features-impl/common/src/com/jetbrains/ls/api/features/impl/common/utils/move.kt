// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.psi.PsiDirectory
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.lsp.protocol.FileRename
import java.io.IOException
import java.nio.file.Path

/**
 * Searches for the [Path] for a move operation.
 */
fun findParentPath(params: List<FileRename>): Path? {
    val files = params.map {
        it.newUri.toPath()?.parent ?: return null
    }.distinct()
    return files.singleOrNull()
}

/**
 * Searches for the [PsiDirectory] for a move operation.
 */
@RequiresReadLock
fun findDestination(project: Project, destinationPath: Path): PsiDirectory? {
    val destinationVFile = VirtualFileManager.getInstance().findFileByNioPath(destinationPath) ?: return null

    return destinationVFile.findPsiDirectory(project)
}

/**
 * Creates the [PsiDirectory] for a move operation.
 */
@RequiresWriteLock
fun createDestination(project: Project, destinationPath: Path): PsiDirectory? {
    return try {
        val destinationVFile = VfsUtil.createDirectoryIfMissing(
            VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL),
            destinationPath.toString()
        )
        destinationVFile?.findPsiDirectory(project)
    } catch (_: IOException) {
        null
    }
}
