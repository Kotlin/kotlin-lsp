// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.api

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.url.VirtualFileUrl

data class WorkspaceEntitySource(override val virtualFileUrl: VirtualFileUrl): EntitySource
