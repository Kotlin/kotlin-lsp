// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Holds back the phase models of an import and emits the last of them once the import ends, so the import
 * publishes a single [ImportEvent.UpdateWorkspaceModel] instead of one per phase. Every
 * other event passes through untouched, so the build output and the unresolved dependencies still arrive
 * while the import runs.
 *
 * A phase is a subset of the phase that follows it (the linked projects of JPS, the generated sources of
 * Maven and Gradle). Committing one on its own is only safe while there is nothing to take away: on a
 * workspace that already has a model, the intermediate commit would replace it with less than it holds,
 * and every reference the last phase brings back would stay unresolved until the import ends. The import
 * cycle decides per target whether to apply this operator.
 *
 * A cancelled import publishes nothing at all: the reload that cancelled it imports everything anew.
 *
 * @param keepModelOnFailure whether a failed import still publishes what it managed to build: `false` when
 *   the target already has a successfully imported model, which the failure then leaves in place; `true`
 *   when it has none, because a partial model resolves more than no model at all.
 */
fun Flow<ImportEvent>.bufferPhases(keepModelOnFailure: Boolean): Flow<ImportEvent> = flow {
    // The whole event, not just its storage, so the republication keeps the build scripts of its phase. Every
    // phase declares them in full, so the last phase's list covers the import.
    var buffered: ImportEvent.UpdateWorkspaceModel? = null
    var failed = false
    suspend fun publishBuffered() {
        buffered?.let { emit(it) }
    }
    try {
        collect { event ->
            when (event) {
                is ImportEvent.UpdateWorkspaceModel -> buffered = event
                else -> {
                    // A reported failure is not the end of the import: JPS reports the one of a linked project and
                    // keeps importing the others, so a better model may still arrive.
                    if (event is ImportEvent.Failed) failed = true
                    emit(event)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (keepModelOnFailure) publishBuffered()
        throw e
    }
    if (keepModelOnFailure || !failed) publishBuffered()
}
