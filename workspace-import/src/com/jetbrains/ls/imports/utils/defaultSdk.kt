// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.api.core.util.createSdkEntity
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import kotlin.io.path.isDirectory

private object DefaultJdkEntitySource : EntitySource

@TestOnly
var DETECT_PROJECT_SDK: Boolean = true

const val DEFAULT_JDK_NAME: String = "Java SDK"

fun MutableEntityStorage.fixMissingProjectSdk(
    defaultSdkPath: Path?,
    virtualFileUrlManager: VirtualFileUrlManager,
) {
    var existingSdk = entities(SdkEntity::class.java).singleOrNull()
    if (existingSdk == null && defaultSdkPath == null && !DETECT_PROJECT_SDK) return
    val path = sdkHome(defaultSdkPath) ?: return
    entities<ModuleEntity>().forEach { module ->
        modifyModuleEntity(module) {
            dependencies = dependencies.map { moduleDependencyItem ->
                when (moduleDependencyItem) {
                    is InheritedSdkDependency -> {
                        existingSdk = existingSdk ?: run {
                            createSdkEntity(
                                name = DEFAULT_JDK_NAME,
                                type = JavaSdk.getInstance(),
                                classRoots = JavaSdkImpl.findClasses(path, false)
                                    .map { it.replace("!/", "!/modules/").intellijUriToLspUri() },
                                sourceRoots = JavaSdkImpl.findSources(path)
                                    .map { it.intellijUriToLspUri() },
                                urlManager = virtualFileUrlManager,
                                source = DefaultJdkEntitySource,
                                storage = this@fixMissingProjectSdk,
                            )
                        }
                        SdkDependency(existingSdk.symbolicId)
                    }

                    else -> moduleDependencyItem
                }
            }.toMutableList()
        }
    }
}

private fun sdkHome(defaultSdkPath: Path?): Path? =
    defaultSdkPath?.also {
        require(it.isDirectory()) { "Configured Java home does not exist or is not a directory: $it" }
    } ?: run {
        val javaHome = JavaHomeFinder.suggestHomePaths(false).firstOrNull()
            ?: System.getProperty("java.home")
            ?: return@run null
        Path.of(javaHome).also {
            require(it.isDirectory()) { "Expected a directory, got $it" }
        }
    }

