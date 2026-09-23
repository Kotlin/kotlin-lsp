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
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.div

@TestDataDirSource
class JpsProjectImportTest : AbstractProjectImportTestCase() {

    @Test
    fun jpsKotlinFacet() = doJpsTest("JpsKotlinFacet")

    /**
     * `.idea/kotlinc.xml` settings reach a module without a Kotlin facet and facets with `useProjectSettings="true"`,
     * whose own arguments are ignored like in the IDE; all come out resolved, with `useProjectSettings=false`.
     * A non-JVM facet keeps its arguments class and gets only the common project arguments, whether it spells out
     * `<compilerArguments>` (moduleC, JS) or only its platform (moduleD, JS).
     */
    @Test
    fun jpsKotlinProjectSettings() = doJpsTest("JpsKotlinProjectSettings") { storage ->
        val settings = storage.entities<KotlinSettingsEntity>().associateBy { it.module.name }
        assertEquals(setOf("moduleA", "moduleB", "moduleC", "moduleD"), settings.keys)
        val jsModules = setOf("moduleC", "moduleD")
        for (entity in settings.values) {
            val name = entity.module.name
            assertEquals(false, entity.useProjectSettings, name)
            assertEquals("-Xjvm-default=all -progressive", entity.compilerSettings?.additionalArguments, name)
            val arguments = entity.compilerArguments.orEmpty()
            val expected = listOf("\"languageVersion\":\"2.3\"", "\"apiVersion\":\"2.3\"") +
                    listOf("\"jvmTarget\":\"21\"").filter { name !in jsModules }
            for (argument in expected) {
                assertTrue(argument in arguments, "$name: $argument not in $arguments")
            }
            if (name in jsModules) {
                assertEquals("JS", entity.targetPlatform, name)
                // "S" is the CompilerArgumentsSerializer prefix of K2JSCompilerArguments.
                assertTrue(arguments.startsWith("S{"), "$name: $arguments")
            }
        }
        // The facet of moduleB says "JVM 1.8", the default JVM platform, which the Kotlin plugin re-derives from the arguments.
        assertEquals("JVM (21)", settings.getValue("moduleA").targetPlatform)
        assertEquals(false, settings.getValue("moduleA").isTestModule)
        assertEquals("JVM (21)", settings.getValue("moduleB").targetPlatform)
        assertEquals(true, settings.getValue("moduleB").isTestModule)
    }

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
