// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import kotlin.collections.plus

internal fun applyChangesWithDeduplication(target: MutableEntityStorage, diff: EntityStorage) {
    for (lib in diff.entities<LibraryEntity>()) {
        if (lib.symbolicId !in target) {
            target.addEntity(
                LibraryEntity(
                    lib.name,
                    lib.tableId,
                    lib.roots,
                    lib.entitySource
                )
            )
        }
    }

    for (sdk in diff.entities<SdkEntity>()) {
        if (sdk.symbolicId !in target) {
            target.addEntity(
                SdkEntity(
                    name = sdk.name,
                    type = sdk.type,
                    roots = sdk.roots,
                    entitySource = sdk.entitySource,
                    additionalData = sdk.additionalData
                ) {
                    version = sdk.version
                    homePath = sdk.homePath
                }
            )
        }
    }

    val moduleMapping = buildMap {
        for (module in diff.entities<ModuleEntity>()) {
            var id = module.symbolicId
            while (true) {
                if (id !in target && (id == module.symbolicId || id !in diff)) break
                id = ModuleId("_${id.name}")
            }
            put(module.symbolicId, id)
        }
    }


    for (module in diff.entities<ModuleEntity>()) {
        target.addEntity(
            ModuleEntity(
                name = moduleMapping[module.symbolicId]!!.name,
                dependencies = emptyList(),
                entitySource = module.entitySource,
            ) {
                contentRoots = module.contentRoots.map { cr ->
                    ContentRootEntity(
                        url = cr.url,
                        excludedPatterns = cr.excludedPatterns,
                        entitySource = cr.entitySource
                    ) {
                        sourceRoots = cr.sourceRoots.map {
                            SourceRootEntity(
                                url = it.url,
                                rootTypeId = it.rootTypeId,
                                entitySource = it.entitySource,
                            )
                        }
                        this.excludedPatterns = cr.excludedPatterns.toMutableList()
                        this.excludedUrls = cr.excludedUrls.map {
                            ExcludeUrlEntity(url = it.url, entitySource = it.entitySource)
                        }.toMutableList()
                    }
                }
            })
    }

    for (module in diff.entities<ModuleEntity>()) {
        val targetModule = target.resolve(moduleMapping[module.symbolicId]!!) as ModuleEntity
        target.modifyModuleEntity(targetModule) {
            dependencies = module.dependencies.map {
                when (it) {
                    is ModuleDependency -> it.copy(module = moduleMapping[it.module]!!)
                    else -> it
                }
            }.toMutableList()
            kotlinSettings += module.kotlinSettings.map { facet ->
                @Suppress("DEPRECATION")
                KotlinSettingsEntity(
                    moduleId = moduleMapping[facet.moduleId]!!,
                    additionalVisibleModuleNames = facet.additionalVisibleModuleNames,
                    entitySource = facet.entitySource,
                    name = facet.name,
                    sourceRoots = facet.sourceRoots,
                    configFileItems = facet.configFileItems,
                    useProjectSettings = facet.useProjectSettings,
                    implementedModuleNames = facet.implementedModuleNames,
                    dependsOnModuleNames = facet.dependsOnModuleNames.map {
                        moduleMapping[ModuleId(it)]!!.name
                    },
                    sourceSetNames = facet.sourceSetNames,
                    isTestModule = facet.isTestModule,
                    externalProjectId = facet.externalProjectId,
                    isHmppEnabled = facet.isHmppEnabled,
                    pureKotlinSourceFolders = facet.pureKotlinSourceFolders,
                    kind = facet.kind,
                    externalSystemRunTasks = facet.externalSystemRunTasks,
                    version = facet.version,
                    flushNeeded = facet.flushNeeded,
                ) {
                    compilerArguments = facet.compilerArguments
                    compilerSettings = facet.compilerSettings
                    targetPlatform = facet.targetPlatform
                    testOutputPath = facet.testOutputPath
                    productionOutputPath = facet.productionOutputPath
                    externalProjectId = facet.externalProjectId
                }
            }
        }
    }
}