// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.jetbrains.ls.imports.api.WorkspaceImportOptions
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.json.WorkspaceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.io.path.div

@TestDataDirSource
class GradleProjectImportTest : GradleProjectImportTestCase() {

    @Test
    fun newIJKotlinGradle() = doGradleTest("NewIJKotlinGradle", JdkDownloaderFacade.jdk21) { workspace: WorkspaceData ->
        withIgnoredJdkRoots(workspace).withRelaxedDependencyOrder()
    }

    @Test
    fun javaLanguageLevels() = doGradleTest("JavaLanguageLevels", JdkDownloaderFacade.jdk21, ::withIgnoredJdkRoots)

    @Test
    fun petClinic() = doGradleTest("PetClinic", ::withIgnoredJdkRoots)

    @Test
    fun brokenPetClinic() = doTestBrokenProject(
        "BrokenPetClinic",
        "Gradle sync failed",
        GradleWorkspaceImporter,
        testDataDir / "gradle",
    )

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL", ::withIgnoredJdkRoots)

    @Test
    fun multiProjectGroovyDSL() = doGradleTest("MultiProjectGroovyDSL", ::withIgnoredJdkRoots)

    @Test
    fun customSourceSets() = doGradleTest("CustomSourceSets", ::withIgnoredJdkRoots)

    @Test
    fun gradleKotlinLanguageVersionCustom() = doGradleTest("GradleKotlinLanguageVersionCustom", ::withIgnoredJdkRoots)

    @Test
    fun gradleKotlinLanguageVersionDefaultFromPlugin() = doGradleTest("GradleKotlinLanguageVersionDefaultFromPlugin", ::withIgnoredJdkRoots)

    @Test
    fun ideaPluginCustomSourceSets() = doGradleTest("IdeaPluginCustomSourceSets", ::withIgnoredJdkRoots)

    @Test
    fun dependencies() = doGradleTest("Dependencies", ::withIgnoredJdkRoots)

    @Test
    fun gradle6Project() = doGradleTest("Gradle6Project", JdkDownloaderFacade.jdk11, ::withIgnoredJdkRoots)

    @Test
    fun gradle7Project() = doGradleTest("Gradle7Project", JdkDownloaderFacade.jdk11, ::withIgnoredJdkRoots)

    @Test
    fun gradleIncludedBuildProject() = doGradleTest("GradleIncludedBuildProject", JdkDownloaderFacade.jdk17) { workspace: WorkspaceData ->
        withIgnoredJdkRoots(workspace).withRelaxedDependencyOrder()
    }

    @Test
    fun empty() = doGradleTest("Empty")

    @Test
    fun gradleProjectWithCustomEnvironment() = doGradleTest(
        project = "GradleProjectWithCustomEnvironment",
        jdkToUse = JdkDownloaderFacade.jdk25,
        resultMapper = ::withIgnoredJdkRoots,
        importParametersCustomizer = {
            it.copy(
                options = WorkspaceImportOptions(
                    environment = mapOf("CUSTOM_ENVIRONMENT_VARIABLE" to "hello_world"),
                    systemProperties = mapOf("intellij.lsp.custom.property" to "world_hello")
                )
            )
        },
        entityStorageVerifier = {}
    )

    @Test
    fun nonExistentDependency() {
        // TODO: Check that missing dependencies are reported
        doGradleTest("NonExistentDependency", ::withIgnoredJdkRoots)
    }

    @Test
    fun gradleProjectWithSourcesAndResourcesInSingleRoot() = doGradleTest(
        "GradleProjectWithSourcesAndResourcesInSingleRoot",
        ::withIgnoredJdkRoots
    )

    @Test
    fun gradleJavaLanguageFeaturePreviewModule() = doGradleTest(
        "GradleJavaLanguageFeaturePreviewModule",
        JdkDownloaderFacade.jdk25,
        ::withIgnoredJdkRoots
    )

    @Test
    // Java 17 should be used to run Gradle
    // Java 21 is expected as the project language level as well as language level for modules
    fun gradleToolchainAndJavaTargetVersion() = doGradleTest(
        "GradleToolchainAndJavaTargetVersion",
        JdkDownloaderFacade.jdk17,
        ::withIgnoredJdkRoots
    )

    @Test
    // Java 17 should be used to run Gradle
    // Java 8 is expected as the project language level as well as language level for modules
    fun gradleToolchainAndJavaSourceVersion() = doGradleTest(
        "GradleToolchainAndJavaSourceVersion",
        JdkDownloaderFacade.jdk17,
        ::withIgnoredJdkRoots
    )

    @Test
    fun gradleJavaLanguageFeaturePreviewSourceSet() = doGradleTest(
        "GradleJavaLanguageFeaturePreviewSourceSet",
        JdkDownloaderFacade.jdk25,
        ::withIgnoredJdkRoots
    )

    @Test
    fun systemPropertiesCheckerProject() = doGradleTest("SystemPropertiesCheckerProject", ::withIgnoredJdkRoots)

    @Test
    fun brokenTaskGraphProject() = doGradleTest("BrokenTaskGraphProject", ::withIgnoredJdkRoots)

    @Test
    fun systemPropertiesCheckerGradle6Project() = doGradleTest(
        "SystemPropertiesCheckerGradle6Project",
        JdkDownloaderFacade.jdk11,
        ::withIgnoredJdkRoots
    )

    @Test
    fun gradleProjectLibrarySourcesAreDownloadedByDefault() =
        doGradleTest(
            project = "GradleProjectLibrarySourcesAreDownloadedByDefault",
            jdkToUse = JdkDownloaderFacade.jdk17,
            resultMapper = ::withIgnoredJdkRoots,
            importParametersCustomizer = { it },
            entityStorageVerifier = { wsm ->
                val libraries = wsm.entities(LibraryEntity::class.java).toList()
                assertEquals(5, libraries.size)
                val targetLibrary = libraries.find { it.name == "Gradle: org.junit.jupiter:junit-jupiter-api:6.1.0" }
                    ?: fail("Required library does not exists in the Workspace Model")
                val libraryRoots = targetLibrary.roots
                assertEquals(2, libraryRoots.size, "Unexpected library root count. Two roots expected: a classes root and a sources root.")
                libraryRoots.find { it.type == LibraryRootTypeId("CLASSES") }.run {
                    assertExists()
                }
                libraryRoots.find { it.type == LibraryRootTypeId("SOURCES") }.run {
                    assertExists()
                }
            }
        )
}
