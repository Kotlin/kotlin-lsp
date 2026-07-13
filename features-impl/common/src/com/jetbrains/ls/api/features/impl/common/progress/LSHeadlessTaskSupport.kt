// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.progress

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.CeProcessCanceledException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.getLockPermitContext
import com.intellij.openapi.progress.prepareThreadContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.TaskSupport
import com.intellij.platform.ide.progress.suspender.TaskSuspender
import com.intellij.util.IntelliJCoroutinesFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

internal class LSHeadlessTaskSupport : TaskSupport {
    override suspend fun <T> withBackgroundProgressInternal(
        project: Project,
        title: @ProgressTitle String,
        cancellation: TaskCancellation,
        suspender: TaskSuspender?,
        visibleInStatusBar: Boolean,
        action: suspend CoroutineScope.() -> T,
    ): T = coroutineScope { action() }

    override suspend fun <T> withModalProgressInternal(
        owner: ModalTaskOwner,
        title: @ProgressTitle String,
        cancellation: TaskCancellation,
        action: suspend CoroutineScope.() -> T,
    ): T = coroutineScope { action() }

    override fun <T> runWithModalProgressBlockingInternal(
        owner: ModalTaskOwner,
        title: @ProgressTitle String,
        cancellation: TaskCancellation,
        action: suspend CoroutineScope.() -> T,
    ): T {
        // Simplified implementation of com.intellij.openapi.progress.impl.PlatformTaskSupport.runWithModalProgressBlockingInternal
        // that doesn't take into account modality.
        return prepareThreadContext { ctx ->
            val (lock, cleanup) = if (ApplicationManager.getApplication().isWriteAccessAllowed) {
                runReadActionBlocking { getLockPermitContext(true) }
            } else {
                getLockPermitContext(true)
            }
            try {
                IntelliJCoroutinesFacade.runBlockingWithParallelismCompensation(ctx + lock, action)
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (ce: CancellationException) {
                throw CeProcessCanceledException(ce)
            } finally {
                cleanup.finish()
            }
        }
    }
}
