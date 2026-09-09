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
import com.jetbrains.lsp.protocol.PrepareRenameParams
import com.jetbrains.lsp.protocol.PrepareRenameResult
import com.jetbrains.lsp.protocol.RenameFilesParams
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.WorkspaceEdit

object LSRename {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun rename(params: RenameParams): WorkspaceEdit? {
        return configuration.entriesFor<LSRenameProvider>(params.textDocument).firstNotNullOfOrNull { it.rename(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun prepareRename(params: PrepareRenameParams): PrepareRenameResult? {
        return configuration.entriesFor<LSRenameProvider>(params.textDocument).firstNotNullOfOrNull { it.prepareRename(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun renameFile(params: RenameFilesParams): WorkspaceEdit? {
        if (params.files.isEmpty()) return null

        val files = params.files

        return when (files.toOperationKind()) {
            OperationKind.MOVE_DIRECTORIES -> {
                configuration.entries<LSMoveDirectoryProvider>().firstNotNullOfOrNull { it.moveDirectory(files) }
            }
            OperationKind.MOVE_FILES -> configuration.entriesFor<LSMoveFileProvider>(files.first().oldUri).firstNotNullOfOrNull { it.moveFile(files) }
            OperationKind.RENAME_DIRECTORY -> {
                // Since it is unclear what language directory is renamed, it is up to callee to decide whether he should rename the directory or not.
                val directory = files.single()
                configuration.entries<LSRenameDirectoryProvider>().firstNotNullOfOrNull { it.renameDirectory(directory) }
            }
            OperationKind.RENAME_FILE -> {
                val file = files.single()
                configuration.entriesFor<LSRenameProvider>(file.oldUri).firstNotNullOfOrNull { it.renameFile(file) }
            }
            OperationKind.UNKNOWN -> null
        }
    }

    private fun isDirectoryOperation(directory: FileRename): Boolean {

        val oldUri = directory.oldUri
        val newUri = directory.newUri
        return oldUri.fileExtension == null && newUri.fileExtension == null
    }

    /**
     * Calculates the [OperationKind] based on the difference in [FileRename]
     */
    private fun List<FileRename>.toOperationKind(): OperationKind {
        return if (isRename(this)) {
            if (isDirectoryOperation(this.single())) OperationKind.RENAME_DIRECTORY else OperationKind.RENAME_FILE
        } else if (isMove(this)) {
            if (all { isDirectoryOperation(it) }) OperationKind.MOVE_DIRECTORIES else OperationKind.MOVE_FILES
        } else {
            OperationKind.UNKNOWN
        }
    }

    private fun isRename(operations: List<FileRename>): Boolean {
        val operation = operations.singleOrNull() ?: return false
        val oldUri = operation.oldUri
        val newUri = operation.newUri

        val oldParent = oldUri.toPath()?.parent ?: return false
        val newParent = newUri.toPath()?.parent ?: return false
        return oldUri.fileName != newUri.fileName && oldParent == newParent
    }

    private fun isMove(operations: List<FileRename>): Boolean {
        return operations.all { operation ->
            val oldUri = operation.oldUri
            val newUri = operation.newUri

            val oldParent = oldUri.toPath()?.parent ?: return@all false
            val newParent = newUri.toPath()?.parent ?: return@all false

            oldUri.fileName == newUri.fileName && oldParent != newParent
        }
    }

    private enum class OperationKind {
        /**
         * Represents a request in which asked to move at least one directory and all elements are directories.
         */
        MOVE_DIRECTORIES,
        /**
         * Represents a request in which asked to move at least one file (of the same language).
         */
        MOVE_FILES,

        /**
         * Represents a request in which asked to rename a single directory.
         */
        RENAME_DIRECTORY,

        /**
         * Represents a request in which asked to rename a single file.
         */
        RENAME_FILE,

        /**
         * Represents an operation not supported yet
         */
        UNKNOWN,
    }
}
