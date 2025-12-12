// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.util.ObjectUtils
import com.jetbrains.ls.snapshot.api.impl.core.SessionDataEntity
import com.jetbrains.lsp.protocol.*
import java.util.concurrent.atomic.AtomicLong

object CompletionItemCache {
    private const val PER_CLIENT_COMPLETION_CACHE_SIZE = 1000L
    private val COMPLETION_CACHE_KEY = ObjectUtils.sentinel("COMPLETION_CACHE_KEY")
    private val COMPLETION_ID_GENERATOR_KEY = ObjectUtils.sentinel("COMPLETION_ID_GENERATOR_KEY")
    val EMPTY_COMPLETION_LIST: CompletionList = CompletionList(isIncomplete = false, items = emptyList())

    /**
     * According to the LSP spec, completion items are sorted by `sortText` field with string comparison.
     *
     * As items are already sorted by kotlin completion, we just generate string which will be sorted the same way
     */
    fun getSortedFieldByIndex(index: Int): String {
        return index.toString().padStart(MAX_INT_DIGITS_COUNT, '0')
    }

    private const val MAX_INT_DIGITS_COUNT = Int.MAX_VALUE.toString().length

    fun emptyTextEdit(position: Position): CompletionItem.Edit {
        val range = Range(position, position)
        return CompletionItem.Edit.InsertReplace(InsertReplaceEdit("", range, range))
    }

    fun <T : Any> cacheCompletionData(data: T): Long {
        val sessionData = SessionDataEntity.single().map

        @Suppress("UNCHECKED_CAST")
        val completionCache = sessionData.getOrPut(COMPLETION_CACHE_KEY) {
            Caffeine.newBuilder()
                .maximumSize(PER_CLIENT_COMPLETION_CACHE_SIZE)
                .build<Long, T>()
        } as Cache<Long, T>

        val id = generateCacheId()
        completionCache.put(id, data)
        return id
    }

    fun <T : Any> getCompletionData(id: Long): T? {
        val userData = SessionDataEntity.single().map

        @Suppress("UNCHECKED_CAST")
        val completionCache = userData[COMPLETION_CACHE_KEY] as Cache<Long, T>
        val completionData = completionCache.getIfPresent(id)
        return completionData
    }

    private fun generateCacheId(): Long {
        val sessionData = SessionDataEntity.single().map
        val idGenerator = sessionData.getOrPut(COMPLETION_ID_GENERATOR_KEY) { AtomicLong(0L) } as AtomicLong
        return idGenerator.incrementAndGet()
    }
}
