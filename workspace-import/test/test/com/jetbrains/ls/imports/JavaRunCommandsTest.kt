// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.java.workspace.entities.JavaModuleSettingsEntity
import com.intellij.java.workspace.entities.javaSettings
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.VirtualFileUrlManagerImpl
import com.jetbrains.ls.api.core.launch.JvmClasspath
import com.jetbrains.ls.imports.java.explicitJavaArgs
import com.jetbrains.ls.imports.java.javaArgs
import com.jetbrains.ls.imports.api.moduleRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class JavaRunCommandsTest {
    private val source = object : EntitySource {}
    private val urls = VirtualFileUrlManagerImpl()
    private val sep = File.pathSeparator

    /**
     * `app` depends on `lib` and on `junit` (TEST); `lib` depends on `guava`. Every module has an output and a
     * test output; `app` has the JDK.
     */
    private fun storage(): MutableEntityStorage {
        val storage = MutableEntityStorage.create()
        storage.addEntity(SdkEntity("17", "JavaSDK", emptyList(), "", source) { homePath = urls.storeAndGet("file:///jdk") })
        fun library(name: String, jar: String) = storage.addEntity(
            LibraryEntity(name, LibraryTableId.ProjectLibraryTableId, listOf(LibraryRoot(urls.storeAndGet("jar://$jar!/"), LibraryRootTypeId.COMPILED)), source)
        )
        library("guava", "/m2/guava.jar")
        library("junit", "/m2/junit.jar")
        fun module(name: String, deps: List<ModuleDependencyItem>, level: String? = null) = storage.addEntity(ModuleEntity(name, deps, source) {
            javaSettings = JavaModuleSettingsEntity(inheritedCompilerOutput = false, excludeOutput = true, entitySource = source) {
                compilerOutput = urls.storeAndGet("file:///out/$name")
                compilerOutputForTests = urls.storeAndGet("file:///out/$name-test")
                languageLevelId = level
            }
        })
        module("lib", listOf(LibraryDependency(LibraryId("guava", LibraryTableId.ProjectLibraryTableId), false, DependencyScope.COMPILE)))
        module(
            "app",
            listOf(
                SdkDependency(SdkId("17", "JavaSDK")),
                ModuleDependency(ModuleId("lib"), false, DependencyScope.COMPILE, false),
                LibraryDependency(LibraryId("junit", LibraryTableId.ProjectLibraryTableId), false, DependencyScope.TEST),
            ),
            level = "JDK_21_PREVIEW",
        )
        return storage
    }

    @Test
    fun `the production runtime has the outputs of the closure and no test entries`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        assertEquals(listOf("/out/app", "/out/lib", "/m2/guava.jar"), runtime.paths)
        assertEquals(listOf("/out/app"), runtime.ownOutputs)
        assertEquals(Path.of("/jdk"), runtime.javaHome)
        assertTrue(runtime.previewFeatures)
    }

    @Test
    fun `the test runtime adds the test output of the module itself and the TEST dependencies`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = true)
        assertEquals(listOf("/out/app", "/out/app-test", "/out/lib", "/m2/guava.jar", "/m2/junit.jar"), runtime.paths)
    }

    @Test
    fun `without a module descriptor the program runs on the class path`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        val args = javaArgs(runtime, "com.acme.Main", vmArgs = listOf("-Xmx1g"), programArgs = listOf("--port", "8080"))
        assertEquals(
            listOf("-Xmx1g", "--enable-preview", "-cp", "/out/app$sep/out/lib$sep/m2/guava.jar", "com.acme.Main", "--port", "8080"),
            args,
        )
        // An entry that names a module falls back to the class too when the outputs hold no descriptor.
        assertTrue("com.acme.Main" in javaArgs(runtime, "acme.app/com.acme.Main", emptyList(), emptyList()))
        assertFalse(javaArgs(runtime, "com.acme.Main", listOf("--enable-preview"), emptyList()).count { it == "--enable-preview" } > 1)
    }

    @Test
    fun `an explicit class path is used as spelled`() {
        val explicit = JvmClasspath(classPath = listOf("/x/a.jar", "/x/classes"), modulePath = listOf("/x/mods"))
        assertEquals(
            listOf("-Xmx1g", "--module-path", "/x/mods", "-cp", "/x/a.jar$sep/x/classes", "com.acme.Main", "--port"),
            explicitJavaArgs(explicit, "com.acme.Main", vmArgs = listOf("-Xmx1g"), programArgs = listOf("--port")),
        )
        assertEquals(
            listOf("-cp", "/x/a.jar", "-m", "acme.app/com.acme.Main"),
            explicitJavaArgs(JvmClasspath(classPath = listOf("/x/a.jar")), "acme.app/com.acme.Main", emptyList(), emptyList()),
        )
    }
}
