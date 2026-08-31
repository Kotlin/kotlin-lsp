// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.workspaceModel.ide.impl.createIdeVirtualFileUrlManager
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.api.withProject
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.api.importWorkspaceFully
import com.jetbrains.ls.imports.core.provider.TestDataDirProvider
import com.jetbrains.ls.imports.core.provider.TestDataDirs
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.utils.DETECT_PROJECT_SDK
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import com.jetbrains.ls.test.api.utils.testPluginSet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.params.Parameter
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.time.Duration.Companion.minutes

abstract class AbstractProjectImportTestCase {

    @Parameter
    protected lateinit var testDataDirProvider: TestDataDirProvider
    private lateinit var testDataDirs: TestDataDirs

    protected val testDataDir: Path
        get() = testDataDirs.testDataDir

    protected val realTestDataDir: Path
        get() = testDataDirs.realTestDataDir

    @BeforeEach
    open fun setUp() {
        testDataDirs = testDataDirProvider.get()
        DETECT_PROJECT_SDK = false
    }

    @AfterEach
    open fun tearDown() {
        testDataDirs.close()
        DETECT_PROJECT_SDK = true
    }

    protected fun doTestBrokenProject(
        project: String,
        failureMessage: String,
        importer: WorkspaceImporter,
        testDataDir: Path,
        failureCause: Class<*> = WorkspaceImportException::class.java
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
                    )
                ) {
                    val result = runCatching {
                        importer.importWorkspaceFully(it.project, WorkspaceImportParameters(projectDir, null), virtualFileUrlManager, reporter)
                    }
                    assertTrue(result.isFailure)
                    val actualFailure = result.exceptionOrNull()!!
                    assertEquals(failureMessage, actualFailure.message)
                    assertTrue(failureCause.isAssignableFrom(actualFailure.javaClass))
                }
            }
        }
    }

    protected fun doTest(
        project: String,
        importer: WorkspaceImporter,
        testDataDir: Path,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it },
        entityStorageVerifier: (EntityStorage) -> Unit = { },
        projectFile: String? = null,
        importParametersCustomizer: (WorkspaceImportParameters) -> WorkspaceImportParameters = { it }
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
                    )
                ) {
                    try {
                        val importPath = projectFile?.let { name -> projectDir / name } ?: projectDir
                        val importParameters = importParametersCustomizer(WorkspaceImportParameters(importPath, null))
                        importer.importWorkspaceFully(it.project, importParameters, virtualFileUrlManager, reporter)
                    } catch (e: WorkspaceImportException) {
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
            importWorkspaceData(data, projectDir, object : EntitySource {}, createIdeVirtualFileUrlManager(true), false, "JSON")
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

    // Sort by jar file name, not the OS-specific absolute path, so the order matches across platforms.
    protected fun LibraryRootData.rootSortKey(): String =
        path.replace('\\', '/').substringAfterLast('/') + " " + type

    protected fun DependencyData.compare(other: DependencyData): Int {
        if (this is DependencyData.Library && other is DependencyData.Library) {
            return this.name.compareTo(other.name)
        }
        return 0
    }

    protected fun withConditionalScopedSystemProperty(condition: () -> Boolean, key: String, value: String, action: () -> Unit) {
        if (condition()) {
            withScopedSystemProperties(key to value, action = action)
        } else {
            action()
        }
    }

    protected fun withScopedSystemProperties(vararg properties: Pair<String, String>, action: () -> Unit) {
        val defaultValues = properties.map { setProperty(it) }
        try {
            action()
        } finally {
            defaultValues.forEach { setProperty(it) }
        }
    }

    protected fun setProperty(property: Pair<String, String?>): Pair<String, String?> {
        if (property.second == null) {
            System.clearProperty(property.first)
            return property.first to null
        } else {
            return property.first to System.setProperty(property.first, property.second!!)
        }
    }

    protected fun LibraryRoot?.assertExists() {
        assertNotNull(this)
        assertTrue(Path.of(url.presentableUrl).exists(), "${url.presentableUrl} should exist on a disk!")
    }
}
