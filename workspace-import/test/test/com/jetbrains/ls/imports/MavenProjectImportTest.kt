// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.customImlData
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.jetbrains.ls.imports.api.IMPORT_JAVA_HOME_KEY
import com.jetbrains.ls.imports.api.externalSystemId
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.div

@TestDataDirSource
class MavenProjectImportTest : AbstractProjectImportTestCase() {

    @Test
    fun simpleMaven() = doMavenTest("SimpleMaven") { storage ->
        // The build command runs the wrapper on the JDK the import ran with. The harness names that JDK through
        // the JVM property, see [doMavenTest].
        val expected = Path.of(System.getProperty("java.home")).toString()
        val mavenModules = storage.entities(ModuleEntity::class.java).filter { it.externalSystemId == "MAVEN" }.toList()
        assertTrue(mavenModules.isNotEmpty(), "The import produced no Maven module")
        for (module in mavenModules) {
            assertEquals(
                expected,
                module.customImlData?.customModuleOptions?.get(IMPORT_JAVA_HOME_KEY),
                "Module ${module.name} carries no Maven JDK stamp"
            )
        }
    }

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

    /**
     * `--enable-preview` in the compiler arguments makes the language level a preview one, which is what tells a launch
     * to pass the flag to the JVM as well (LSP-1745). The Gradle counterparts are
     * [GradleProjectImportTest.gradleJavaLanguageFeaturePreviewModule] and
     * [GradleProjectImportTest.gradleJavaLanguageFeaturePreviewSourceSet].
     */
    @Test
    fun mavenJavaLanguageFeaturePreview() = doMavenTest("MavenJavaLanguageFeaturePreview")

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
