package com.jetbrains.ls.api.features.impl.common.core

import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.ReadonlyStatusHandler.OperationStatus
import com.intellij.openapi.vfs.VirtualFile

internal class MockReadonlyStatusHandler : ReadonlyStatusHandler() {
    override fun ensureFilesWritable(files: Collection<VirtualFile?>): OperationStatus {
        return OperationStatusImp
    }
}

private object OperationStatusImp : OperationStatus() {
    override fun getReadonlyFiles(): Array<out VirtualFile?> = VirtualFile.EMPTY_ARRAY
    override fun hasReadonlyFiles(): Boolean = false
    override fun getReadonlyFilesMessage(): @NlsContexts.DialogMessage String = error("No readonly files")
}