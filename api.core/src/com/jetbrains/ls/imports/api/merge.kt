// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.api

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities

fun MutableEntityStorage.applyChangesWithDeduplication(diff: EntityStorage) {
    val mutableDiff = when (diff) {
        is MutableEntityStorage -> diff
        is ImmutableEntityStorage -> MutableEntityStorage.from(diff)
        else -> error("Unsupported diff type: ${diff::class.simpleName}")
    }

    mutableDiff.entities<ModuleEntity>().forEach { m ->
        var module = m
        while (entities<ModuleEntity>().any { it.name == module.name }) {
            module = mutableDiff.modifyModuleEntity(module) {
                name = "_${module.name}"
            }
        }
    }

    applyChangesFrom(mutableDiff)
}