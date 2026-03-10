// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.openapi.application.PathManager
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
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import com.jetbrains.ls.imports.utils.DETECT_PROJECT_SDK
import com.jetbrains.ls.test.api.utils.testApplicationInits
import com.jetbrains.ls.test.api.utils.testPluginSet
import com.jetbrains.ls.test.api.utils.testProjectInits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class ProjectImportTest {
    private val testDataDir = PathManager.getHomeDir() /
            "language-server" / "community" / "workspace-import" / "test" / "testData"

    @BeforeEach
    fun setUp() { DETECT_PROJECT_SDK = false }

    @AfterEach
    fun tearDown() { DETECT_PROJECT_SDK = true }

    @Test
    fun newIJKotlinGradle() = doGradleTest("NewIJKotlinGradle", ::withIgnoredJdk)

    @Test
    fun petClinic() = doGradleTest("PetClinic", ::withIgnoredJdk)

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL", ::withIgnoredJdk)

    @Test
    fun multiProjectGroovyDSL() = doGradleTest("MultiProjectGroovyDSL", ::withIgnoredJdk)

    @Test
    fun customSourceSets() = doGradleTest("CustomSourceSets", ::withIgnoredJdk)

    @Test
    fun dependencies() = doGradleTest("Dependencies", ::withIgnoredJdk)

    @Test
    fun gradle6Project() = doGradleTest("Gradle6Project", ::withIgnoredJdk)

    @Test
    fun gradle7Project() = doGradleTest("Gradle7Project", ::withIgnoredJdk)

    @Test
    fun empty() = doGradleTest("Empty")

    @Test
    fun nonExistentDependency() {
        // TODO: Check that missing dependencies are reported
        doGradleTest("NonExistentDependency", ::withIgnoredJdk)
    }

    @Test
    fun simpleMaven() = doMavenTest("SimpleMaven")

    private fun doGradleTest(project: String, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) {
        downloadGradleBinaries()
        doTest(project, GradleWorkspaceImporter, testDataDir / "gradle", resultMapper)
    }

    private fun doMavenTest(project: String) {
        downloadMavenBinaries().let { path ->
            MavenWorkspaceImporter.useMavenAndJava(path, Path.of(System.getProperty("java.home")))
        }
        doTest(project, MavenWorkspaceImporter, testDataDir / "maven")
    }

    private fun doTest(
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

        val expectedData: WorkspaceData = try {
            val expectedDataPath = projectDir / "workspace.json"
            val rawExpectedData: WorkspaceData = expectedDataPath.inputStream()
                .use { stream ->
                    @OptIn(ExperimentalSerializationApi::class)
                    Json.decodeFromStream(stream)
                }
            resultMapper(rawExpectedData)
        } catch (e: Exception) {
            throw e
        }

        assertEquals(cropJarPaths(toJson(expectedData)), cropJarPaths(toJson(data)))

        val storageFromData = MutableEntityStorage.create().apply {
            importWorkspaceData(data, projectDir, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true))
        }
        assertEquals(data, workspaceData(storageFromData, projectDir))
    }

    // 1. ~/.gradle/ paths contain random hashes
    // 2. on Windows kotlin compiler arguments contain double-escaped '\' (i.e. '\\\\')
    // 3. TC Windows agents use Z:\gradle\caches\
    fun cropJarPaths(jsonString: String): String =
        """[^"]*?gradle/caches/([^"]*?)/[^/.]*?/([^/"]*\.jar[\\"])""".toRegex()
            .replace(jsonString.replace("\\\\\\\\", "/").replace("\\\\", "/")) {
                """<GRADLE_REPO>/${it.groupValues[1]}/#####/${it.groupValues[2]}"""
            }

    /**
     * If there is no explicitly defined java version in a Gradle project, Gradle will use the version from JAVA_HOME.
     * JAVA_HOME may differ between environments, thus it's simply to ignore the target java version for such projects.
     * Todo LSP-790
     */
    private fun withIgnoredJdk(data: WorkspaceData): WorkspaceData {
        return WorkspaceData(
            data.modules.map { module ->
                module.copy(
                    dependencies = module.dependencies.map { dependency ->
                        return@map if (dependency is DependencyData.Sdk) {
                            dependency.copy(
                                name = "ignored"
                            )
                        } else {
                            dependency
                        }
                    }
                )
            },
            data.libraries,
            data.sdks.map {
                it.copy(
                    version = "ignored",
                    name = "ignored",
                    roots = emptyList(),
                    homePath = "/"
                )
            },
            data.kotlinSettings,
            data.javaSettings.map {
                assertNotNull(it.languageLevelId, "Module language level should never be null!")
                it.copy(
                    languageLevelId = "ignored"
                )
            }
        )
    }
}