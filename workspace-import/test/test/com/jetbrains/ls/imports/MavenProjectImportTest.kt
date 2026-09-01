// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.div

@TestDataDirSource
class MavenProjectImportTest : AbstractProjectImportTestCase() {

    @Test
    fun simpleMaven() = doMavenTest("SimpleMaven")

    @Test
    fun mavenCustomPomName() = doMavenTest("MavenCustomPomName", projectFile = "dev_pom.xml") { storage ->
        // The launch/build path re-runs Maven with the recorded build file (`-f`); losing the stamp would
        // silently rebuild from a conventional pom that this project does not have.
        val stamped = storage.entities(ModuleEntity::class.java).map { it.exModuleOptions?.rootProjectPath }.toList()
        assertTrue(stamped.isNotEmpty() && stamped.all { it?.endsWith("dev_pom.xml") == true }, stamped.toString())
    }

    /** The project's own `maven-install-plugin` configuration must not break the plugin install step. */
    @Test
    fun mavenInstallPluginConfigured() = doMavenTest("MavenInstallPluginConfigured")

    @Test
    fun mavenAnnotationProcessing() = doMavenTest("MavenAnnotationProcessing")

    @Test
    fun mavenKotlinLanguageVersionFromConfiguration() = doMavenTest("MavenKotlinLanguageVersionFromConfiguration")

    @Test
    fun mavenKotlinLanguageVersionFromProperty() = doMavenTest("MavenKotlinLanguageVersionFromProperty")

    @Test
    fun mavenKotlinLanguageVersionFromPluginVersion() = doMavenTest("MavenKotlinLanguageVersionFromPluginVersion")

    private fun doMavenTest(
        project: String,
        projectFile: String? = null,
        entityStorageVerifier: (EntityStorage) -> Unit = { },
    ) {
        downloadMavenBinaries().let { path ->
            MavenWorkspaceImporter.useMavenAndJava(path, Path.of(System.getProperty("java.home")))
        }
        doTest(
            project,
            MavenWorkspaceImporter,
            testDataDir / "maven",
            projectFile = projectFile,
            entityStorageVerifier = entityStorageVerifier
        )
    }
}
