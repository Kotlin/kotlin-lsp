// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(LLFirInternals::class, KaImplementationDetail::class, KaPlatformInterface::class)

package com.jetbrains.ls.api.features.impl.kotlin.apiImpl

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.analyzer.kotlin.invalidate
import com.jetbrains.analyzer.kotlin.registerLLFirSessionServices
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import com.jetbrains.ls.snapshot.api.impl.core.AnalyzerContextKind
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceEvent
import com.jetbrains.ls.snapshot.api.impl.core.rocks.toList
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.caches.cleanable.NoOpValueReferenceCleaner
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.cache.LLFirSessionCacheStorage

/**
 * Caches [LLFirSessionCacheStorage] from Kotlin LL FIR.
 *
 * The storage is part of the RocksDB-backed workspace state and is semi-immutable: new entries can be added, while
 * invalidation creates and publishes a copy. Requests that captured an older workspace state can continue using their
 * own [LLFirSessionCacheStorage].
 *
 * At the same time, there may exist multiple [LLFirSessionCacheStorage] instances owned by concurrent requests.
 * Some of those storages may share LL FIR sessions. Sessions are not invalidated by cleaner
 * ([NoOpValueReferenceCleaner]), so they live while at least one request uses them.
 */
internal object LLFirSessionCacheStorageComponent : WorkspaceComponent<LLFirSessionCacheStorage> {
    override fun init(): LLFirSessionCacheStorage =
        newStorage()

    override fun handleEvent(event: WorkspaceEvent, state: LLFirSessionCacheStorage): LLFirSessionCacheStorage =
        when (event) {
            is WorkspaceEvent.InvalidateFiles -> state.invalidate(event.files.toList(), AnalyzerContext.current.project)
            is WorkspaceEvent.WorkspaceModelChanged -> state
            WorkspaceEvent.LowMemory -> newStorage()
        }

    override fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: LLFirSessionCacheStorage,
        contextKind: AnalyzerContextKind,
    ) {
    }

    override fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: LLFirSessionCacheStorage,
        contextKind: AnalyzerContextKind,
    ) {
        val storage = when (contextKind) {
            AnalyzerContextKind.WRITE -> LLFirSessionCacheStorage.createEmpty {
                @Suppress("INVISIBLE_REFERENCE")
                org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.cache.LLFirSessionCleaner(it.requestedDisposableOrNull)
            }
            else -> state
        }
        builder.registerLLFirSessionServices(project, storage, contextKind == AnalyzerContextKind.WRITE)
    }

    private fun newStorage(): LLFirSessionCacheStorage =
        LLFirSessionCacheStorage.createEmpty { NoOpValueReferenceCleaner() }
}
