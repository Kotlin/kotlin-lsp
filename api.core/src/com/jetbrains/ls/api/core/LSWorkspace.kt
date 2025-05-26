// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

