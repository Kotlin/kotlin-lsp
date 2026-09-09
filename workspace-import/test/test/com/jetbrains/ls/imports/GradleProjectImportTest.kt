// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.jetbrains.ls.imports.api.WorkspaceImportOptions
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.io.path.div

@TestDataDirSource
class GradleProjectImportTest : GradleProjectImportTestCase() {

    @Test
    fun newIJKotlinGradle() = doGradleTest(
        "NewIJKotlinGradle",
        JdkDownloaderFacade.jdk21,
        WorkspaceComparator().withIgnoredJdkRoots().withRelaxedDependencyOrder()
    )

    @Test
    fun javaLanguageLevels() = doGradleTest("JavaLanguageLevels", JdkDownloaderFacade.jdk21, WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun petClinic() = doGradleTest("PetClinic", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun brokenPetClinic() = doTestBrokenProject(
        "BrokenPetClinic",
        "Gradle sync failed",
        GradleWorkspaceImporter,
        testDataDir / "gradle",
    )

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun kotlinKmpProject() {
        val reporter = LoggingWorkspaceProgressReporter()
        doGradleTest(
            project = "GradleKotlinKmpProject",
            jdkToUse = JdkDownloaderFacade.jdk17,
            reporter = reporter,
            // No golden comparison: the KMP model varies by operating system and by network. The test asserts only
            // that the fix ran each sync task once and that the import returned the project's module graph.
            comparator = WorkspaceComparator.NONE,
            importParametersCustomizer = { it },
            entityStorageVerifier = { storage ->
                val moduleNames = storage.entities(ModuleEntity::class.java).map { it.name }.toSet()
                val expected = setOf(
                    "KotlinProject",
                    "KotlinProject.shared",
                    "KotlinProject.shared.jvmMain",
                    "KotlinProject.desktopApp",
                    "KotlinProject.desktopApp.main",
                )
                assertTrue(
                    moduleNames.containsAll(expected),
                    "Imported model is missing KMP modules. Expected at least $expected but got $moduleNames"
                )
            }
        )
        reporter.capturedOutput
            .apply {
                assertContainsOnce("Gradle execution complete")
                assertContainsOnce("Task :prepareKotlinIdeaImport")
                assertContainsOnce("Task :desktopApp:prepareKotlinIdeaImport")
                assertContainsOnce("Task :shared:prepareKotlinIdeaImport")
            }
    }

    @Test
    fun multiProjectGroovyDSL() = doGradleTest("MultiProjectGroovyDSL", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun customSourceSets() = doGradleTest("CustomSourceSets", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun gradleKotlinLanguageVersionCustom() = doGradleTest("GradleKotlinLanguageVersionCustom", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun gradleKotlinLanguageVersionDefaultFromPlugin() = doGradleTest("GradleKotlinLanguageVersionDefaultFromPlugin", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun ideaPluginCustomSourceSets() = doGradleTest("IdeaPluginCustomSourceSets", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun dependencies() = doGradleTest("Dependencies", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun gradle6Project() = doGradleTest("Gradle6Project", JdkDownloaderFacade.jdk11, WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun gradle7Project() = doGradleTest("Gradle7Project", JdkDownloaderFacade.jdk11, WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun gradleIncludedBuildProject() = doGradleTest(
        "GradleIncludedBuildProject",
        JdkDownloaderFacade.jdk17,
        WorkspaceComparator().withIgnoredJdkRoots().withRelaxedDependencyOrder()
    )

    @Test
    fun empty() = doGradleTest("Empty")

    @Test
    fun gradleProjectWithCustomEnvironment() = doGradleTest(
        project = "GradleProjectWithCustomEnvironment",
        jdkToUse = JdkDownloaderFacade.jdk25,
        comparator = WorkspaceComparator().withIgnoredJdkRoots(),
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
        doGradleTest("NonExistentDependency", WorkspaceComparator().withIgnoredJdkRoots())
    }

    @Test
    fun gradleProjectWithSourcesAndResourcesInSingleRoot() = doGradleTest(
        "GradleProjectWithSourcesAndResourcesInSingleRoot",
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    fun gradleJavaLanguageFeaturePreviewModule() = doGradleTest(
        "GradleJavaLanguageFeaturePreviewModule",
        JdkDownloaderFacade.jdk25,
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    // Java 17 should be used to run Gradle
    // Java 21 is expected as the project language level as well as language level for modules
    fun gradleToolchainAndJavaTargetVersion() = doGradleTest(
        "GradleToolchainAndJavaTargetVersion",
        JdkDownloaderFacade.jdk17,
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    // Java 17 should be used to run Gradle
    // Java 8 is expected as the project language level as well as language level for modules
    fun gradleToolchainAndJavaSourceVersion() = doGradleTest(
        "GradleToolchainAndJavaSourceVersion",
        JdkDownloaderFacade.jdk17,
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    fun gradleJavaLanguageFeaturePreviewSourceSet() = doGradleTest(
        "GradleJavaLanguageFeaturePreviewSourceSet",
        JdkDownloaderFacade.jdk25,
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    fun systemPropertiesCheckerProject() = doGradleTest("SystemPropertiesCheckerProject", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun brokenTaskGraphProject() = doGradleTest("BrokenTaskGraphProject", WorkspaceComparator().withIgnoredJdkRoots())

    @Test
    fun buildExceptionProject() = doTestBrokenProject(
        "GradleBuildExceptionProject",
        "Gradle sync failed",
        GradleWorkspaceImporter,
        testDataDir / "gradle",
    )

    @Test
    fun systemPropertiesCheckerGradle6Project() = doGradleTest(
        "SystemPropertiesCheckerGradle6Project",
        JdkDownloaderFacade.jdk11,
        WorkspaceComparator().withIgnoredJdkRoots()
    )

    @Test
    fun gradleProjectLibrarySourcesAreDownloadedByDefault() =
        doGradleTest(
            project = "GradleProjectLibrarySourcesAreDownloadedByDefault",
            jdkToUse = JdkDownloaderFacade.jdk17,
            comparator = WorkspaceComparator().withIgnoredJdkRoots(),
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

    private fun String.assertContainsOnce(value: String) {
        val firstIndex = indexOf(value)
        assertTrue(firstIndex > 0, "The line '$value' is not found in string [$this]")
        val secondIndex = indexOf(value, firstIndex + value.length, false)
        assertTrue(
            secondIndex == -1,
            "The line '$value' expected to be found in a string only once, but the value was found at $firstIndex and $secondIndex"
        )
    }
}
