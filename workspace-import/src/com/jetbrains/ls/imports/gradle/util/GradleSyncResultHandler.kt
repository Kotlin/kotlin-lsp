// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.util

import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ResultHandler
import java.util.concurrent.CompletableFuture

internal class GradleSyncResultHandler<T> {

    private val future: CompletableFuture<T> = CompletableFuture()

    fun asIntermediateHandler(): IntermediateResultHandler<T> = IntermediateResultHandler {
        result -> future.complete(result)
    }

    fun asResultHandler(): ResultHandler<Void?> = object : ResultHandler<Void?> {
        override fun onComplete(result: Void?) = Unit
        override fun onFailure(failure: GradleConnectionException?) {
            if (failure == null) {
                future.completeExceptionally(GradleConnectionException("Gradle sync failed"))
            }
            future.completeExceptionally(failure)
        }
    }

    fun getSyncResult(): T = future.get()
}
