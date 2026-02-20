// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.DefaultJdkConfigurator
import com.intellij.openapi.projectRoots.JavaSdk
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
import com.jetbrains.lsp.protocol.URI
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import kotlin.io.path.isDirectory

private object DefaultJdkEntitySource : EntitySource

@TestOnly
var DETECT_PROJECT_SDK: Boolean = true

internal fun MutableEntityStorage.fixMissingProjectSdk(
    defaultSdkPath: Path?,
    virtualFileUrlManager: VirtualFileUrlManager,
) {
    var existingSdk = entities(SdkEntity::class.java).singleOrNull()
    if (existingSdk == null && defaultSdkPath == null && !DETECT_PROJECT_SDK) return
    entities<ModuleEntity>().forEach { module ->
        modifyModuleEntity(module) {
            dependencies = dependencies.map { moduleDependencyItem ->
                when (moduleDependencyItem) {
                    is InheritedSdkDependency -> {
                        existingSdk = existingSdk ?: createSdkEntity(
                            name = "Java SDK",
                            type = JavaSdk.getInstance(),
                            roots = sdkRoots(defaultSdkPath),
                            urlManager = virtualFileUrlManager,
                            source = DefaultJdkEntitySource,
                            storage = this@fixMissingProjectSdk,
                        )
                        SdkDependency(existingSdk.symbolicId)
                    }

                    else -> moduleDependencyItem
                }
            }.toMutableList()
        }
    }
}

private fun sdkRoots(defaultSdkPath: Path?): List<URI> {
    val path = defaultSdkPath?.let {
        require(it.isDirectory()) { "Configured Java home does not exist or is not a directory: $it" }
        it
    } ?: run {
        val jdkConfigurator = ApplicationManager.getApplication().getService(DefaultJdkConfigurator::class.java)
        Path.of(jdkConfigurator.guessJavaHome() ?: System.getProperty("java.home")).also {
            require(it.isDirectory()) { "Expected a directory, got $it" }
        }
    }
    return JavaSdkImpl.findClasses(path, false).map { (it.replace("!/", "!/modules/").intellijUriToLspUri()) }
}

