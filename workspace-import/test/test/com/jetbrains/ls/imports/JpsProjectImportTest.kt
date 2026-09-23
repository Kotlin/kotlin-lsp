// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.java.workspace.entities.asJavaResourceRoot
import com.intellij.java.workspace.entities.asJavaSourceRoot
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.TestModulePropertiesEntity
import com.intellij.platform.workspace.jps.entities.testProperties
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.div

@TestDataDirSource
class JpsProjectImportTest : AbstractProjectImportTestCase() {

    @Test
    fun jpsKotlinFacet() = doJpsTest("JpsKotlinFacet")

    @Test
    fun jpsJavaModule() = doJpsTest("JpsJavaModule")

    @Test
    fun jpsExportedModuleLibrary() = doJpsTest("JpsExportedModuleLibrary")

    @Test
    fun jpsPackagePrefix() = doJpsTest("JpsPackagePrefix") { storage ->
        val roots = storage.entities<SourceRootEntity>().associateBy { it.url.fileName }
        assertEquals("com.foo", roots.getValue("src").asJavaSourceRoot()?.packagePrefix)
        assertEquals(true, roots.getValue("gen").asJavaSourceRoot()?.generated)
        assertEquals("META-INF", roots.getValue("resources").asJavaResourceRoot()?.relativeOutputPath)
    }

    @Test
    fun jpsTestModuleProperties() = doJpsTest("JpsTestModuleProperties") { storage ->
        val properties = storage.entities<TestModulePropertiesEntity>().single()
        assertEquals("foo.tests", properties.module.name)
        assertEquals("foo", properties.productionModuleId.name)
        val production = storage.entities<ModuleEntity>().single { it.name == "foo" }
        assertEquals(null, production.testProperties)
    }

    private fun doJpsTest(project: String, entityStorageVerifier: (EntityStorage) -> Unit = { }) {
        doTest(
            project, JpsWorkspaceImporter, testDataDir / "jps",
            entityStorageVerifier = entityStorageVerifier,
            // A TC Windows agent can have no discoverable JDK inside the Bazel sandbox.
            importParametersCustomizer = { it.copy(defaultSdkPath = Path(System.getProperty("java.home"))) },
        )
    }
}
