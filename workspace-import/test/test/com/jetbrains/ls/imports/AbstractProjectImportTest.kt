// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

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
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
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

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidMultiModule() = doGradleTest("AndroidMultiModule") { workspaceData ->
        withIgnoredJdk(workspaceData).withSanitizedJarLibraryNames().withoutJavaSettings()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
    fun androidDependingOnKotlinJvm() = doGradleTest("AndroidDependingOnKotlinJvm") { workspaceData ->
        withIgnoredJdk(workspaceData).withSanitizedJarLibraryNames().withoutJavaSettings()
    }


    protected fun doGradleTest(project: String, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) {
        downloadGradleBinaries()
        doTest(project, GradleWorkspaceImporter, testDataDir / "gradle", resultMapper)
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

    /**
     * If there is no explicitly defined java version in a Gradle project, Gradle will use the version from JAVA_HOME.
     * JAVA_HOME may differ between environments, thus it's simply to ignore the target java version for such projects.
     * Todo LSP-790
     */
    protected fun withIgnoredJdk(data: WorkspaceData): WorkspaceData {
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
                    homePath = null
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

    private fun WorkspaceData.withoutJavaSettings(): WorkspaceData {
        return copy(javaSettings = emptyList())
    }
}
