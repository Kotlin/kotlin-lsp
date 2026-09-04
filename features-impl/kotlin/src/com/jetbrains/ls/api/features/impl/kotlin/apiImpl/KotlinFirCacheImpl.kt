// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(LLFirInternals::class, KaImplementationDetail::class, KaPlatformInterface::class)

package com.jetbrains.ls.api.features.impl.kotlin.apiImpl

import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.api.AnalyzerContextKind
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.analyzer.filesystem.FileUrlList
import com.jetbrains.analyzer.filesystem.toList
import com.jetbrains.analyzer.kotlin.invalidate
import com.jetbrains.analyzer.kotlin.registerLLFirSessionServices
import com.jetbrains.ls.snapshot.api.impl.core.KotlinFirCache
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.caches.cleanable.NoOpValueReferenceCleaner
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.cache.LLFirSessionCacheStorage

/**
 * [KotlinFirCache] over [LLFirSessionCacheStorage] from Kotlin LL FIR.
 *
 * The storage is semi-immutable: new entries can be added, while invalidation creates and
 * publishes a copy. Requests that captured an older workspace state can continue using their own
 * [LLFirSessionCacheStorage].
 *
 * At the same time, there may exist multiple [LLFirSessionCacheStorage] instances owned by
 * concurrent requests. Some of those storages may share LL FIR sessions. Sessions are not
 * invalidated by cleaner ([NoOpValueReferenceCleaner]), so they live while at least one request
 * uses them.
 */
internal class KotlinFirCacheImpl private constructor(
    private val storage: LLFirSessionCacheStorage,
) : KotlinFirCache {

    override fun invalidate(files: FileUrlList): KotlinFirCache =
        KotlinFirCacheImpl(storage.invalidate(files.toList(), AnalyzerContext.current.project))

    override fun dropCaches(): KotlinFirCache =
        KotlinFirCacheImpl(newStorage())

    override fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        contextKind: AnalyzerContextKind,
    ) {
        val containerStorage = when (contextKind) {
            is AnalyzerContextKind.Isolated -> LLFirSessionCacheStorage.createEmpty {
                @Suppress("INVISIBLE_REFERENCE")
                org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.cache.LLFirSessionCleaner(it.requestedDisposableOrNull)
            }
            else -> storage
        }
        builder.registerLLFirSessionServices(project, containerStorage, contextKind is AnalyzerContextKind.Isolated)
    }

    companion object {
        fun new(): KotlinFirCacheImpl =
            KotlinFirCacheImpl(newStorage())

        private fun newStorage(): LLFirSessionCacheStorage =
            LLFirSessionCacheStorage.createEmpty { NoOpValueReferenceCleaner() }
    }
}
