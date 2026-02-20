// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(LLFirInternals::class, KaImplementationDetail::class, KaPlatformInterface::class)

package com.jetbrains.ls.api.features.impl.kotlin.apiImpl

import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.kotlin.invalidate
import com.jetbrains.analyzer.kotlin.registerLLFirSessionServices
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.AnalyzerContainerType
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceModelEntity
import com.jetbrains.rhizomedb.ChangeScope
import com.jetbrains.rhizomedb.EID
import com.jetbrains.rhizomedb.Entity
import com.jetbrains.rhizomedb.EntityType
import com.jetbrains.rhizomedb.delete
import com.jetbrains.rhizomedb.exists
import com.jetbrains.rhizomedb.set
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.caches.cleanable.NoOpValueReferenceCleaner
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCacheStorage

/**
 * Caches [LLFirSessionCacheStorage] from Kotlin LL FIR inside.
 *
 * The entity exits only in a single instance and the stored [LLFirSessionCacheStorage] is 'semi-immutable' meaning that
 * new entries can be added there but no entries can be removed.
 *
 * When a modification is performed via the [getInvalidationEvent]/[LLFirInvalidationEvent.tryApplyingIfUpToDate],
 * the copy of an existing snapshot of `LFirSessionCacheStorage` is created.
 * We perform all the session invalidation to this copy and only then publish it.
 * All requests who had the previous version of the [LLFirInvalidationEvent], will continue to safely use their version of [LLFirSessionCacheStorage].
 *
 * At the same time, there may exist multiple [LLFirSessionCacheStorage] each owned by different requests being performed concurrently.
 * Some of those storages may share some [LLFirSession].
 * The [LLFirSession] is not invalidated ([LLFirSession.isValid] is always `true` as we use [NoOpValueReferenceCleaner])
 * so they will exist as long as there is at least one request that uses it.
 */
internal data class LLFirSessionCacheStorageEntity(override val eid: EID) : Entity {
    companion object : EntityType<LLFirSessionCacheStorageEntity>(
        LLFirSessionCacheStorageEntity::class,
        ::LLFirSessionCacheStorageEntity
    ) {
        val Storage: Required<LLFirSessionCacheStorage> = requiredTransient("LLFirSessionCacheStorage")

        context(changeScope: ChangeScope)
        fun new(): LLFirSessionCacheStorageEntity =
            LLFirSessionCacheStorageEntity.new {
                it[Storage] = LLFirSessionCacheStorage.createEmpty { NoOpValueReferenceCleaner() }
            }
    }

    val storage: LLFirSessionCacheStorage by Storage
}

@OptIn(LLFirInternals::class)
internal fun AnalyzerContainerBuilder.registerLLFirSessionServices(
    project: Project,
    containerType: AnalyzerContainerType,
) {
    val storage = when (containerType) {
        AnalyzerContainerType.WRITE -> LLFirSessionCacheStorage.createEmpty {
            @Suppress("INVISIBLE_REFERENCE")
            org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCleaner(it.requestedDisposableOrNull)
        }
        else -> LLFirSessionCacheStorageEntity.single().storage
    }
    registerLLFirSessionServices(project, storage, containerType == AnalyzerContainerType.WRITE)
}


context(server: LSServer)
suspend fun filesInvalidation(fileUrls: List<FileUrl>): context(ChangeScope) () -> Unit {
    return LLFirSessionCacheStorageEntity.singleOrNull()?.let { entity ->
        val storage = server.withWriteAnalysisContext {
            entity.storage.invalidate(fileUrls, project)
        };

        {
            if (entity.exists()) {
                entity[LLFirSessionCacheStorageEntity.Storage] = storage
            }
        }
    } ?: {}
}

fun resetLLFirSessionCacheEntity(): context(ChangeScope) (WorkspaceModelEntity) -> Unit {
    return { _ ->
        LLFirSessionCacheStorageEntity.singleOrNull()?.delete()
        LLFirSessionCacheStorageEntity.new()
    }
}
