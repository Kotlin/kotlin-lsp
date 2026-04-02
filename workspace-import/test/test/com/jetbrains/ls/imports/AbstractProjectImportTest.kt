// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloadItem
import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.imports.api.EmptyWorkspaceProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter.LSP_GRADLE_JAVA_HOME_PROPERTY
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import com.jetbrains.ls.imports.utils.DETECT_PROJECT_SDK
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import com.jetbrains.ls.test.api.utils.testApplicationInits
import com.jetbrains.ls.test.api.utils.testPluginSet
import com.jetbrains.ls.test.api.utils.testProjectInits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

abstract class AbstractProjectImportTest {
    protected abstract val testDataDir: Path

    @BeforeEach
    open fun setUp() {
        DETECT_PROJECT_SDK = false
    }

    @AfterEach
    open fun tearDown() {
        DETECT_PROJECT_SDK = true
    }

    @Test
    fun newIJKotlinGradle() = doGradleTest("NewIJKotlinGradle", JdkDownloaderFacade.jdk21, ::withIgnoredJdkRoots)

    @Test
    fun petClinic() = doGradleTest("PetClinic", ::withIgnoredJdkRoots)

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL", ::withIgnoredJdkRoots)

    @Test
    fun multiProjectGroovyDSL() = doGradleTest("MultiProjectGroovyDSL", ::withIgnoredJdkRoots)

    @Test
    fun customSourceSets() = doGradleTest("CustomSourceSets", ::withIgnoredJdkRoots)

    @Test
    fun dependencies() = doGradleTest("Dependencies", ::withIgnoredJdkRoots)

    @Test
    fun gradle6Project() = doGradleTest("Gradle6Project", JdkDownloaderFacade.jdk11, ::withIgnoredJdkRoots)

    @Test
    fun gradle7Project() = doGradleTest("Gradle7Project", JdkDownloaderFacade.jdk11, ::withIgnoredJdkRoots)

    @Test
    fun empty() = doGradleTest("Empty")

    @Test
    fun nonExistentDependency() {
        // TODO: Check that missing dependencies are reported
        doGradleTest("NonExistentDependency", ::withIgnoredJdkRoots)
    }

    @Test
    fun simpleMaven() = doMavenTest("SimpleMaven")

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidMultiModule() = doGradleTest("AndroidMultiModule") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidDependingOnKotlinJvm() = doGradleTest("AndroidDependingOnKotlinJvm") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames()
    }

    protected fun doGradleTest(project: String, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) =
        doGradleTest(project, JdkDownloaderFacade.jdk17, resultMapper)

    protected fun doGradleTest(project: String, jdkToUse: JdkDownloadItem, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) {
        downloadGradleBinaries()
        withScopedSystemProperty(LSP_GRADLE_JAVA_HOME_PROPERTY, jdkToUse.home.toString()) {
            doTest(project, GradleWorkspaceImporter, testDataDir / "gradle", resultMapper)
        }
    }

    protected fun doMavenTest(project: String) {
        downloadMavenBinaries().let { path ->
            MavenWorkspaceImporter.useMavenAndJava(path, Path.of(System.getProperty("java.home")))
        }
        doTest(project, MavenWorkspaceImporter, testDataDir / "maven")
    }

    protected fun doTest(
        project: String,
        importer: WorkspaceImporter,
        testDataDir: Path,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it }
    ) {
        val projectDir = testDataDir / project
        require(projectDir.exists()) { "Project $project not found at $projectDir" }

        val storage = runBlocking(Dispatchers.Default) {
            withAnalyzer(isUnitTestMode = true) { analyzer ->
                val currentSnapshot = WorkspaceModelSnapshot.empty()
                val virtualFileUrlManager = currentSnapshot.virtualFileUrlManager
                analyzer.withProject(
                    analyzerProjectConfigForImport(
                        fileSystems = AnalyzerFileSystems.default(),
                        projectId = AnalyzerProjectId(),
                        entities = currentSnapshot.entityStore,
                        urlManager = virtualFileUrlManager,
                        pluginSet = testPluginSet,
                        applicationInits = testApplicationInits,
                        projectInits = testProjectInits,
                    )
                ) {
                    importer.importWorkspace(it.project, projectDir, null, virtualFileUrlManager, EmptyWorkspaceProgressReporter())
                }
            }
        }

        if (storage == null) {
            assertFalse((projectDir / "workspace.json").exists(), "Workspace import failed")
            return
        }

        val data = resultMapper(workspaceData(storage, projectDir))
        compareWithTestdata(projectDir / "workspace.json", cropJarPaths(toJson(data)))

        val storageFromData = MutableEntityStorage.create().apply {
            importWorkspaceData(data, projectDir, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true))
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

    protected fun withIgnoredJdkRoots(data: WorkspaceData): WorkspaceData = data.copy(
        sdks = data.sdks.map {
            it.copy(
                roots = emptyList(),
                homePath = null
            )
        }
    )

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
}
