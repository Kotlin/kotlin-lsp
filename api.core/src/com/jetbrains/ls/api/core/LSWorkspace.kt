package com.jetbrains.ls.api.core

import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.TestOnly

interface LSWorkspaceStructure {

    suspend fun updateWorkspaceModelDirectly(updater: suspend CoroutineScope.(VirtualFileUrlManager, MutableEntityStorage) -> Unit)

    @TestOnly
    fun getEntityStorage(): ImmutableEntityStorage
}

