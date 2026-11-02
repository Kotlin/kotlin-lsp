// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.customImlData
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.entities
import com.jetbrains.ls.imports.api.IMPORT_JAVA_HOME_KEY
import com.jetbrains.ls.buildtool.api.WorkspaceImportOptions
import com.jetbrains.ls.imports.api.externalSystemId
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.gradle.GradleDriver
import com.jetbrains.ls.imports.utils.DEFAULT_JDK_NAME
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.reader

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
        GradleDriver,
        testDataDir / "gradle",
    )

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL", WorkspaceComparator().withIgnoredJdkRoots())

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
        project = "GradleToolchainAndJavaTargetVersion",
        jdkToUse = JdkDownloaderFacade.jdk17,
        comparator = WorkspaceComparator().withIgnoredJdkRoots(),
        importParametersCustomizer = toolchainJdks(JdkDownloaderFacade.jdk21),
        entityStorageVerifier = { storage -> assertToolchainSdks(storage, "GradleToolchainAndJavaTargetVersion") }
    )

    @Test
    // Java 17 should be used to run Gradle
    // Java 8 is expected as the project language level as well as language level for modules
    // The project toolchain is 17, but the compile tasks override it with 21: the SDK is named and versioned 21
    fun gradleToolchainAndJavaSourceVersion() = doGradleTest(
        project = "GradleToolchainAndJavaSourceVersion",
        jdkToUse = JdkDownloaderFacade.jdk17,
        comparator = WorkspaceComparator().withIgnoredJdkRoots(),
        importParametersCustomizer = toolchainJdks(JdkDownloaderFacade.jdk21),
        entityStorageVerifier = { storage -> assertToolchainSdks(storage, "GradleToolchainAndJavaSourceVersion") }
    )

    /**
     * Gradle runs on JDK 17, the toolchain is JDK 21: the source sets compile with a JDK 21 SDK, the root module
     * keeps the Gradle JDK, and the stamps name the JDK that ran Gradle.
     */
    private fun assertToolchainSdks(storage: EntityStorage, project: String) {
        val gradleJdk = JdkDownloaderFacade.jdk17.home
        val modules = storage.entities<ModuleEntity>().filter { it.externalSystemId == "GRADLE" }.associateBy { it.name }
        val sdks = storage.entities<SdkEntity>().toList()
        assertTrue(modules.isNotEmpty(), "The import produced no Gradle module")
        assertTrue(sdks.none { it.name == DEFAULT_JDK_NAME }, "No default JDK is added: ${sdks.map { it.name }}")

        fun sdkOf(moduleName: String): SdkEntity {
            val module = modules[moduleName] ?: fail("No module $moduleName in ${modules.keys}")
            assertTrue(module.dependencies.none { it is InheritedSdkDependency }, "Module $moduleName inherits the project SDK")
            val sdkId = module.dependencies.filterIsInstance<SdkDependency>().single().sdk
            return sdks.single { it.symbolicId == sdkId }
        }

        fun SdkEntity.realHome(): Path? = homePath?.presentableUrl?.let { Path(it).toRealPath() }

        assertEquals(gradleJdk.toRealPath(), sdkOf(project).realHome(), "Root module SDK home")
        // Gradle may pick any JDK 21 it knows, not only the pinned one.
        for (sourceSet in listOf("$project.main", "$project.test")) {
            val sdk = sdkOf(sourceSet)
            val home = sdk.realHome() ?: fail("Module $sourceSet SDK has no home")
            assertEquals("JDK_21", sdk.version, "Module $sourceSet SDK version")
            assertNotEquals(gradleJdk.toRealPath(), home, "Module $sourceSet compiles with the Gradle JDK")
            assertTrue(javaVersionOf(home).startsWith("21"), "Module $sourceSet SDK home $home is not a JDK 21")
        }

        for (module in modules.values) {
            assertEquals(
                gradleJdk.toString(),
                module.customImlData?.customModuleOptions?.get(IMPORT_JAVA_HOME_KEY),
                "Module ${module.name} carries no Gradle JDK stamp"
            )
        }
    }

    /** `JAVA_VERSION` of the JDK `release` file. */
    private fun javaVersionOf(home: Path): String {
        val properties = Properties()
        (home / "release").reader().use { properties.load(it) }
        return properties.getProperty("JAVA_VERSION").orEmpty().trim('"')
    }

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
        GradleDriver,
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
}
