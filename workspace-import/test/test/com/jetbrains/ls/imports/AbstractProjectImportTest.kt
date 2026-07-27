// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloadItem
import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.NioFiles
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.util.SystemProperties
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.api.withProject
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_JAVA_HOME_PROPERTY
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_PROJECT_INIT_SCRIPTS
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import com.jetbrains.ls.imports.utils.DETECT_PROJECT_SDK
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import com.jetbrains.ls.test.api.utils.testApplicationInitsForImport
import com.jetbrains.ls.test.api.utils.testPluginSet
import com.jetbrains.ls.test.api.utils.testProjectInits
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.fail
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

private val LOG = logger<AbstractProjectImportTest>()

private const val GRADLE_CLEANUP_ATTEMPTS = 3
private const val GRADLE_CLEANUP_RETRY_DELAY_MS = 300L

abstract class AbstractProjectImportTest {
    protected abstract val testDataDir: Path

    @BeforeEach
    open fun setUp() {
        DETECT_PROJECT_SDK = false
    }

    @AfterEach
    open fun tearDown() {
        DETECT_PROJECT_SDK = true
        System.clearProperty(LSP_GRADLE_PROJECT_INIT_SCRIPTS)
    }

    @Test
    fun newIJKotlinGradle() = doGradleTest("NewIJKotlinGradle", JdkDownloaderFacade.jdk21) { workspace: WorkspaceData ->
        withIgnoredJdkRoots(workspace).withRelaxedDependencyOrder()
    }

    @Test
    fun petClinic() = doGradleTest("PetClinic", ::withIgnoredJdkRoots)

    @Test
    fun brokenPetClinic() = doTestBrokenProject(
        "BrokenPetClinic",
        "The supplied build action failed with an exception.",
        GradleWorkspaceImporter,
        testDataDir / "gradle"
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
    fun systemPropertiesCheckerProject() = doGradleTest("SystemPropertiesCheckerProject", ::withIgnoredJdkRoots)

    @Test
    fun systemPropertiesCheckerGradle6Project() = doGradleTest(
        "SystemPropertiesCheckerGradle6Project",
        JdkDownloaderFacade.jdk11,
        ::withIgnoredJdkRoots
    )

    @Test
    fun jpsKotlinFacet() = doJpsTest("JpsKotlinFacet")

    @Test
    fun jpsJavaModule() = doJpsTest("JpsJavaModule")

    @Test
    fun simpleMaven() = doMavenTest("SimpleMaven")

    @Test
    fun mavenKotlinLanguageVersionFromConfiguration() = doMavenTest("MavenKotlinLanguageVersionFromConfiguration")

    @Test
    fun mavenKotlinLanguageVersionFromProperty() = doMavenTest("MavenKotlinLanguageVersionFromProperty")

    @Test
    fun mavenKotlinLanguageVersionFromPluginVersion() = doMavenTest("MavenKotlinLanguageVersionFromPluginVersion")

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidMultiModule() = doGradleTest("AndroidMultiModule") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames().withoutSyntheticLibraries()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidDependingOnKotlinJvm() = doGradleTest("AndroidDependingOnKotlinJvm") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames().withoutSyntheticLibraries()
    }

    private fun doJpsTest(project: String,) {
        doTest(project, JpsWorkspaceImporter, testDataDir / "jps")
    }

    @Test
    fun gradleProjectLibrarySourcesAreDownloadedByDefault() = withCustomUserHome { gradleUserHomePath ->
        withScopedSystemProperty(GradleToolingApiHelper.LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY, gradleUserHomePath) {
            doGradleTest("GradleProjectLibrarySourcesAreDownloadedByDefault", JdkDownloaderFacade.jdk17, ::withIgnoredJdkRoots, { wsm ->
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
            })
        }
    }

    protected fun doGradleTest(project: String, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) =
        doGradleTest(project, JdkDownloaderFacade.jdk17, resultMapper) { }

    protected fun doGradleTest(
        project: String,
        jdkToUse: JdkDownloadItem,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it }
    ) = doGradleTest(project, jdkToUse, resultMapper) { }

    protected fun doGradleTest(
        project: String,
        jdkToUse: JdkDownloadItem,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it },
        entityStorageVerifier: (EntityStorage) -> Unit
    ) {
        downloadGradleBinaries()
        withConditionalScopedSystemProperty(
            condition = { System.getenv("TEAMCITY_VERSION") != null && !project.contains("android", true) },
            key = LSP_GRADLE_PROJECT_INIT_SCRIPTS,
            value = getCacheRedirectorInitScriptPath().toString()
        ) {
            withScopedSystemProperty(key = LSP_GRADLE_JAVA_HOME_PROPERTY, value = jdkToUse.home.toString()) {
                doTest(project, GradleWorkspaceImporter, testDataDir / "gradle", resultMapper, entityStorageVerifier)
            }
        }
    }

    protected fun doMavenTest(project: String) {
        downloadMavenBinaries().let { path ->
            MavenWorkspaceImporter.useMavenAndJava(path, Path.of(System.getProperty("java.home")))
        }
        doTest(project, MavenWorkspaceImporter, testDataDir / "maven")
    }

    protected fun doTestBrokenProject(
        project: String,
        failureMessage: String,
        importer: WorkspaceImporter,
        testDataDir: Path,
    ) {
        val projectDir = testDataDir / project
        require(projectDir.exists()) { "Project $project not found at $projectDir" }

        val reporter = LoggingWorkspaceProgressReporter()
        timeoutRunBlocking(timeout = 10.minutes) {
            withAnalyzer(isUnitTestMode = true) { analyzer ->
                val currentSnapshot = WorkspaceModelSnapshot.empty()
                val virtualFileUrlManager = currentSnapshot.virtualFileUrlManager
                analyzer.withProject(
                    analyzerProjectConfigForImport(
                        projectId = AnalyzerProjectId(),
                        entities = currentSnapshot.entityStore,
                        urlManager = virtualFileUrlManager,
                        pluginSet = testPluginSet,
                        applicationInits = testApplicationInitsForImport,
                        projectInits = testProjectInits,
                    )
                ) {
                    val result = runCatching {
                        importer.importWorkspace(it.project, WorkspaceImportParameters(projectDir, null), virtualFileUrlManager, reporter)
                    }
                    assertTrue(result.isFailure)
                    assertEquals(failureMessage, result.exceptionOrNull()!!.message)
                }
            }
        }
    }

    protected fun doTest(
        project: String,
        importer: WorkspaceImporter,
        testDataDir: Path,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it },
        entityStorageVerifier: (EntityStorage) -> Unit = { }
    ) {
        val projectDir = testDataDir / project
        require(projectDir.exists()) { "Project $project not found at $projectDir" }

        val reporter = LoggingWorkspaceProgressReporter()
        val storage = timeoutRunBlocking(timeout = 10.minutes) {
            withAnalyzer(isUnitTestMode = true) { analyzer ->
                val currentSnapshot = WorkspaceModelSnapshot.empty()
                val virtualFileUrlManager = currentSnapshot.virtualFileUrlManager
                analyzer.withProject(
                    analyzerProjectConfigForImport(
                        projectId = AnalyzerProjectId(),
                        entities = currentSnapshot.entityStore,
                        urlManager = virtualFileUrlManager,
                        pluginSet = testPluginSet,
                        applicationInits = testApplicationInitsForImport,
                        projectInits = testProjectInits,
                    )
                ) {
                    try {
                        importer.importWorkspace(it.project, WorkspaceImportParameters(projectDir, null), virtualFileUrlManager, reporter)
                    }
                    catch (e: WorkspaceImportException) {
                        throw AssertionError(
                            "Import of '$project' failed: ${e.message}\n" +
                            "logMessage: ${e.logMessage}\n" +
                            "---- tool output ----\n${reporter.capturedOutput}",
                            e
                        )
                    }
                }
            }
        }

        if (storage == null) {
            assertFalse((projectDir / "workspace.json").exists(), "Workspace import failed")
            return
        }

        entityStorageVerifier(storage)

        val data = resultMapper(workspaceData(storage, projectDir))
        compareWithTestdata(projectDir / "workspace.json", cropJarPaths(toJson(data)))

        val storageFromData = MutableEntityStorage.create().apply {
            importWorkspaceData(data, projectDir, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true), false, "JSON")
        }
        assertEquals(data, workspaceData(storageFromData, projectDir))
    }

    // 1. ~/.gradle/ paths contain random hashes
    // 2. on Windows kotlin compiler arguments contain double-escaped '\' (i.e. '\\\\')
    // 3. TC Windows agents use Z:\gradle\caches\
    // 4. Files from env.ANDROID_HOME will be cropped
    protected fun cropJarPaths(jsonString: String): String {
        var result = jsonString
        result = result.replace("\\\\\\\\", "/").replace("\\\\", "/")
        result = """[^"]*gradle/caches/([^"]*?)/[^/.]*/([^/"]*\.jar[\\"])""".toRegex()
            .replace(result) {
                "<GRADLE_REPO>/${it.groupValues[1]}/#####/${it.groupValues[2]}"
            }

        result = """[^"]*gradle/wrapper/dists/([^/]*)/[^/]*/([^"]*\.jar")""".toRegex()
            .replace(result) {
                "<GRADLE_DIST>/${it.groupValues[1]}/#####/${it.groupValues[2]}"
            }

        val androidHome = System.getenv("ANDROID_HOME")?.let(::Path)
        val userHome = System.getProperty("user.home")?.let(::Path)

        if (androidHome != null && userHome != null) {
            val expectedAndroidHomeNotation = if (androidHome.startsWith(userHome)) {
                "<HOME>/" + androidHome.relativeTo(userHome)
            } else androidHome.pathString

            result = result.replace(expectedAndroidHomeNotation, "<ANDROID_HOME>")
        }

        return result
    }

    protected open fun getRealTestDataDir(): String = testDataDir.toString()

    protected fun withIgnoredJdkRoots(data: WorkspaceData): WorkspaceData = data.copy(
        sdks = data.sdks.map {
            it.copy(
                roots = emptyList(),
                homePath = null
            )
        }
    )

    private fun WorkspaceData.withoutSyntheticLibraries(): WorkspaceData {
        val testDataPath = getRealTestDataDir()
        return copy(
            modules = modules.map { moduleData ->
                moduleData.copy(
                    dependencies = moduleData.dependencies.filter { dependencyData ->
                        !(dependencyData is DependencyData.Library && dependencyData.name.contains(testDataPath))
                    }
                )
            }
        )
    }

    private fun WorkspaceData.withRelaxedDependencyOrder(): WorkspaceData = copy(
        modules = modules.map { moduleData ->
            moduleData.copy(
                dependencies = moduleData.dependencies.sortedWith { first, second -> first.compare(second) }
            )
        },
        libraries = libraries.sortedBy { it.name }
            .map { library -> library.copy(roots = library.roots.sortedBy { it.rootSortKey() }) }
    )

    // Sort by jar file name, not the OS-specific absolute path, so the order matches across platforms.
    private fun LibraryRootData.rootSortKey(): String =
        path.replace('\\', '/').substringAfterLast('/') + " " + type

    private fun DependencyData.compare(other: DependencyData): Int {
        if (this is DependencyData.Library && other is DependencyData.Library) {
            return this.name.compareTo(other.name)
        }
        return 0
    }

    /**
     * Some 'adhoc' libraries were not resolved by coordinates, but as 'jars' directly.
     * The jar path is used as part of the library name, which shall be sanitized for tests
     */
    private fun WorkspaceData.withSanitizedJarLibraryNames(): WorkspaceData {
        val jarLibraryRegex = Regex("""Gradle: (?<path>.*\.jar)""")

        return copy(
            libraries = libraries.map { library ->
                val match = jarLibraryRegex.matchEntire(library.name) ?: return@map library
                val path = Path(match.groups["path"]!!.value)
                library.copy(name = "Gradle: #####/${path.fileName}")
            }
        )
    }

    private fun withConditionalScopedSystemProperty(condition: () -> Boolean, key: String, value: String, action: () -> Unit) {
        if (condition()) {
            withScopedSystemProperty(key, value, action)
        } else {
            action()
        }
    }

    private fun withScopedSystemProperty(key: String, value: String, action: () -> Unit) {
        val originalValue = System.getProperty(key)
        try {
            System.setProperty(key, value)
            action()
        } finally {
            if (originalValue == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun Path.recreateDir() {
        deleteRecursively()
        createDirectory()
    }

    private fun LibraryRoot?.assertExists() {
        assertNotNull(this)
        assertTrue(Path.of(url.presentableUrl).exists(), "${url.presentableUrl} should exist on a disk!")
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withCustomUserHome(action: (String) -> Unit) {
        val gradleUserHomePath = Path.of(getRealTestDataDir()).resolve(".gradle")
        gradleUserHomePath.recreateDir()
        try {
            val systemUserHome = getGradleUserHome() ?: return
            copyGradleDistribution(systemUserHome, gradleUserHomePath)
            action(gradleUserHomePath.toString())
        } finally {
            deleteRecursivelyBestEffort(gradleUserHomePath)
        }
    }

    // The Gradle daemon may still hold files in '.gradle' right after import; a cleanup-only failure
    // must not fail an otherwise-passing test. Retry briefly to let handles release, then give up quietly.
    private fun deleteRecursivelyBestEffort(path: Path) {
        repeat(GRADLE_CLEANUP_ATTEMPTS) { attempt ->
            try {
                NioFiles.deleteRecursively(path)
                return
            }
            catch (e: IOException) {
                if (attempt == GRADLE_CLEANUP_ATTEMPTS - 1) {
                    LOG.warn("Best-effort cleanup of $path failed; leaving it for the test runner to reclaim", e)
                    return
                }
                Thread.sleep(GRADLE_CLEANUP_RETRY_DELAY_MS)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyGradleDistribution(gradleUserHome: Path, newGradleUserHome: Path) {
        assertTrue(newGradleUserHome.exists())
        val source = gradleUserHome.resolve("wrapper/dists")
        if (!source.exists()) {
            return
        }
        val destination = newGradleUserHome.resolve("wrapper/dists")
        assertFalse(destination.exists())
        destination.createDirectories()
        source.copyToRecursively(destination, { _, _, exception -> throw exception }, false, false)
    }

    private fun getGradleUserHome(): Path? {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME") ?: System.getProperty("gradle.user.home")
        if (gradleUserHome != null) {
            return Path.of(gradleUserHome)
        }
        val userHome = SystemProperties.getUserHome()
        return Path.of(userHome).resolve(".gradle")
    }

    private fun getCacheRedirectorInitScriptPath(): Path {
        return createTempFile("lsp-test-cache-redirector-patch", ".gradle").also {
            it.writeText(
                """
                allprojects {
                    repositories {
                        maven {
                            url = 'https://repo.labs.intellij.net/repo1'
                        }
                    }
                }
            """.trimIndent()
            )
        }
    }
}
