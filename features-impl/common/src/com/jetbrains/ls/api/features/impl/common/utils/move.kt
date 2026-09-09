// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.psi.PsiDirectory
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.lsp.protocol.FileRename


/**
 * Searches for the destination directory for a move operation.
 * @param params The list of file movements.
 */
@RequiresReadLock
fun findDestination(project: Project, params: List<FileRename>): PsiDirectory? {
    if (params.any { it.newUri.findVirtualFile() != null }) return null

    val files = params.map {
        val parent = it.newUri.toPath()?.parent ?: return null
        VirtualFileManager.getInstance().findFileByNioPath(parent) ?: return null
    }.distinct()

    val destination = files.singleOrNull() ?: return null

    return destination.findPsiDirectory(project)
}