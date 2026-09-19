// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.platform.workspace.jps.entities.ModuleCustomImlDataEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.customImlData
import com.intellij.platform.workspace.jps.entities.modifyModuleCustomImlDataEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.imports.api.IMPORT_JAVA_HOME_KEY
import com.jetbrains.ls.imports.api.externalSystemId
import org.jetbrains.annotations.ApiStatus

/**
 * Records [javaHome] under [IMPORT_JAVA_HOME_KEY] on every module whose external system is [externalSystemId].
 * A `null` or blank [javaHome] records nothing: an empty `JAVA_HOME` would break the wrapper.
 *
 * An existing custom-options entity keeps its other entries. A module without one gets a new entity with the
 * module's entity source. The Gradle importer and the Maven importer call this with their own external system id.
 */
@ApiStatus.Internal
fun MutableEntityStorage.stampBuildToolJavaHome(externalSystemId: String, javaHome: String?) {
    if (javaHome.isNullOrBlank()) return
    val stamp = mapOf(IMPORT_JAVA_HOME_KEY to javaHome)
    for (module in entities<ModuleEntity>().filter { it.externalSystemId == externalSystemId }.toList()) {
        val existingCustomImlData = module.customImlData
        if (existingCustomImlData == null) {
            modifyModuleEntity(module) {
                customImlData = ModuleCustomImlDataEntity(stamp, module.entitySource)
            }
        }
        else {
            modifyModuleCustomImlDataEntity(existingCustomImlData) {
                customModuleOptions = customModuleOptions + stamp
            }
        }
    }
}
