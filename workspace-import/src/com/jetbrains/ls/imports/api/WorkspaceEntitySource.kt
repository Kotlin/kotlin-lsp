package com.jetbrains.ls.imports.api

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.url.VirtualFileUrl

data class WorkspaceEntitySource(override val virtualFileUrl: VirtualFileUrl): EntitySource
