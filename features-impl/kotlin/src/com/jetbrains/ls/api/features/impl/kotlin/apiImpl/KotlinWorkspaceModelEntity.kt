// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.apiImpl

import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.analyzer.kotlin.KotlinWorkspaceModelCaches
import com.jetbrains.analyzer.kotlin.createKotlinWorkspaceModelCaches
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceModelEntity
import com.jetbrains.rhizomedb.ChangeScope
import com.jetbrains.rhizomedb.EID
import com.jetbrains.rhizomedb.Entity
import com.jetbrains.rhizomedb.EntityType
import com.jetbrains.rhizomedb.RefFlags
import com.jetbrains.rhizomedb.entity

data class KotlinWorkspaceModelEntity(override val eid: EID) : Entity {
    companion object : EntityType<KotlinWorkspaceModelEntity>(KotlinWorkspaceModelEntity::class, ::KotlinWorkspaceModelEntity) {
        val CacheAttr: Required<KotlinWorkspaceModelCaches> = requiredTransient("kotlinWorkspaceModelCaches")

        val WorkspaceModelEntityAttr: Required<WorkspaceModelEntity> =
            requiredRef("workspaceModel", RefFlags.CASCADE_DELETE_BY, RefFlags.UNIQUE)

        context(_: ChangeScope)
        fun new(
            kotlinWorkspaceModelCaches: KotlinWorkspaceModelCaches,
            workspaceModelEntity: WorkspaceModelEntity
        ): KotlinWorkspaceModelEntity =
            KotlinWorkspaceModelEntity.new {
                it[CacheAttr] = kotlinWorkspaceModelCaches
                it[WorkspaceModelEntityAttr] = workspaceModelEntity
            }
    }

    val caches: KotlinWorkspaceModelCaches by CacheAttr
}

val WorkspaceModelEntity.kotlinWorkspaceModel: KotlinWorkspaceModelEntity?
    get() = entity(KotlinWorkspaceModelEntity.WorkspaceModelEntityAttr, this)

fun resetKotlinWorkspaceModelEntity(context: AnalyzerContext): context(ChangeScope) (WorkspaceModelEntity) -> Unit {
    val caches = createKotlinWorkspaceModelCaches(context.project)
    return { workspaceModelEntity ->
        KotlinWorkspaceModelEntity.new(caches, workspaceModelEntity)
    }
}