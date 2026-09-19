// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.platform.workspace.jps.entities.ExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.jps.entities.ModuleCustomImlDataEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntityBuilder
import com.intellij.platform.workspace.jps.entities.customImlData
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.imports.api.IMPORT_JAVA_HOME_KEY
import com.jetbrains.ls.imports.maven.stampMavenJavaHome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GradleJavaHomeStampTest {
    private val javaHome = "/jdks/jbrsdk-25"
    private val testEntitySource = object : EntitySource {}

    @Test
    fun `gradle module without custom options gets a new entity`() {
        val storage = MutableEntityStorage.create()
        storage.addGradleModule("app")

        storage.stampGradleJavaHome(javaHome)

        val custom = storage.module("app").customImlData!!
        assertEquals(javaHome, custom.customModuleOptions[IMPORT_JAVA_HOME_KEY])
        assertEquals(testEntitySource, custom.entitySource)
    }

    @Test
    fun `existing custom options are kept`() {
        val storage = MutableEntityStorage.create()
        storage.addGradleModule("app") {
            customImlData = ModuleCustomImlDataEntity(mapOf("other" to "value"), testEntitySource)
        }

        storage.stampGradleJavaHome(javaHome)

        val custom = storage.module("app").customImlData!!.customModuleOptions
        assertEquals("value", custom["other"])
        assertEquals(javaHome, custom[IMPORT_JAVA_HOME_KEY])
    }

    @Test
    fun `a module of another system is not stamped`() {
        val storage = MutableEntityStorage.create()
        storage.addEntity(ModuleEntity("jps", emptyList(), testEntitySource))
        storage.addEntity(ModuleEntity("maven", emptyList(), testEntitySource) {
            exModuleOptions = ExternalSystemModuleOptionsEntity(testEntitySource) { externalSystem = "MAVEN" }
        })

        storage.stampGradleJavaHome(javaHome)

        assertNull(storage.module("jps").customImlData)
        assertNull(storage.module("maven").customImlData)
    }

    @Test
    fun `a blank java home stamps nothing`() {
        val storage = MutableEntityStorage.create()
        storage.addGradleModule("app")

        storage.stampGradleJavaHome(" ")

        assertNull(storage.module("app").customImlData)
    }

    /** The Maven importer stamps only its own modules through the same helper. */
    @Test
    fun `the maven stamp skips a gradle module`() {
        val storage = MutableEntityStorage.create()
        storage.addGradleModule("app")
        storage.addEntity(ModuleEntity("maven", emptyList(), testEntitySource) {
            exModuleOptions = ExternalSystemModuleOptionsEntity(testEntitySource) { externalSystem = "MAVEN" }
        })

        storage.stampMavenJavaHome(javaHome)

        val custom = storage.module("maven").customImlData!!.customModuleOptions
        assertEquals(javaHome, custom[IMPORT_JAVA_HOME_KEY])
        assertNull(storage.module("app").customImlData)
    }

    private fun MutableEntityStorage.addGradleModule(name: String, init: ModuleEntityBuilder.() -> Unit = {}) {
        addEntity(ModuleEntity(name, emptyList(), testEntitySource) {
            exModuleOptions = ExternalSystemModuleOptionsEntity(testEntitySource) { externalSystem = "GRADLE" }
            init()
        })
    }

    private fun MutableEntityStorage.module(name: String): ModuleEntity = entities<ModuleEntity>().single { it.name == name }
}
