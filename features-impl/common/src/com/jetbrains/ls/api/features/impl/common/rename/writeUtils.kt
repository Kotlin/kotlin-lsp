// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.application.WriteIntentReadAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// For Language Server only (it does not have real EDT). Do not use anywhere else
internal suspend fun <T> writeIntentUserReadAction(action: () -> T): T {
    return withContext(Dispatchers.EDT) {
        var result: T? = null
        (TransactionGuard.getInstance() as TransactionGuardImpl).performUserActivity {
            // AnalyzerLaterInvocator does not use NonBlockingFlushQueue
            // so WriteIntentReadAction is still required
            result = WriteIntentReadAction.compute<T> {
                action()
            }
        }
        result ?: throw IllegalStateException("result should not be null")
    }
}