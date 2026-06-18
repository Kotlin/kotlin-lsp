// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.rename

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.fileExtension
import com.jetbrains.ls.api.core.util.fileName
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.move.LSMoveDirectoryProvider
import com.jetbrains.ls.api.features.move.LSMoveFileProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.RenameFilesParams
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.WorkspaceEdit

object LSRename {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun rename(params: RenameParams): WorkspaceEdit? {
        return configuration.entriesFor<LSRenameProvider>(params.textDocument).firstNotNullOfOrNull { it.rename(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun renameFile(params: RenameFilesParams): WorkspaceEdit? {
        val fileRename = params.files.singleOrNull() ?: return null

        return when (fileRename.toOperationKind()) {
            OperationKind.MOVE_DIRECTORY -> configuration.entries<LSMoveDirectoryProvider>().firstNotNullOfOrNull { it.moveDirectory(fileRename) }
            OperationKind.MOVE_FILE -> configuration.entriesFor<LSMoveFileProvider>(fileRename.oldUri).firstNotNullOfOrNull { it.moveFile(fileRename) }
            OperationKind.RENAME_DIRECTORY -> {
                // Since it is unclear what language directory is renamed, it is up to callee to decide whether he should rename the directory or not.
                configuration.entries<LSRenameDirectoryProvider>().firstNotNullOfOrNull { it.renameDirectory(fileRename) }
            }
            OperationKind.RENAME_FILE -> {
                configuration.entriesFor<LSRenameProvider>(fileRename.oldUri).firstNotNullOfOrNull { it.renameFile(fileRename) }
            }
            OperationKind.UNKNOWN -> null
        }
    }

    private fun isDirectoryOperation(rename: FileRename): Boolean {
        val oldUri = rename.oldUri
        val newUri = rename.newUri
        return oldUri.fileExtension == null && newUri.fileExtension == null
    }

    /**
     * Calculates the [OperationKind] based on the difference in [FileRename]
     */
    private fun FileRename.toOperationKind(): OperationKind {
        return if (isRename(this)) {
            if (isDirectoryOperation(this)) OperationKind.RENAME_DIRECTORY else OperationKind.RENAME_FILE
        } else if (isMove(this)) {
            if (isDirectoryOperation(this)) OperationKind.MOVE_DIRECTORY else OperationKind.MOVE_FILE
        } else {
            OperationKind.UNKNOWN
        }
    }

    private fun isRename(operation: FileRename): Boolean {
        val oldUri = operation.oldUri
        val newUri = operation.newUri

        val oldParent = oldUri.toPath()?.parent ?: return false
        val newParent = newUri.toPath()?.parent ?: return false
        return oldUri.fileName != newUri.fileName && oldParent == newParent
    }

    private fun isMove(operation: FileRename): Boolean {
        val oldUri = operation.oldUri
        val newUri = operation.newUri

        val oldParent = oldUri.toPath()?.parent ?: return false
        val newParent = newUri.toPath()?.parent ?: return false

        return oldUri.fileName == newUri.fileName && oldParent != newParent
    }

    private enum class OperationKind {
        MOVE_DIRECTORY,
        MOVE_FILE,
        RENAME_DIRECTORY,
        RENAME_FILE,
        UNKNOWN,
    }
}
