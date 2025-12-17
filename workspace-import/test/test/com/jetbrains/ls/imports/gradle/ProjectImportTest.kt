// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.application.PathManager
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.api.defaultPluginSet
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.json.workspaceModel
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

class ProjectImportTest {
    private val testDataDir = PathManager.getHomeDir() /
            "language-server" / "community" / "workspace-import" / "test" / "testData"

    @Test
    fun petClinic() = doGradleTest("PetClinic")

    @Test
    fun multiProjectKotlinDSL() = doGradleTest("MultiProjectKotlinDSL")

    @Test
    fun multiProjectGroovyDSL() = doGradleTest("MultiProjectGroovyDSL")

    @Test
    fun customSourceSetts() = doGradleTest("CustomSourceSets")

    @Test
    fun dependencies() = doGradleTest("Dependencies")

    @Test
    fun empty() = doGradleTest("Empty")

    @Test
    fun nonExistentDependency() {
        // TODO: Check that missing dependencies are reported
        doGradleTest("NonExistentDependency")
    }

    private fun doGradleTest(project: String) =
        doTest(project, GradleWorkspaceImporter, testDataDir / "gradle")

    private fun doTest(project: String, importer: WorkspaceImporter, testDataDir: Path) {
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
                        pluginSet = defaultPluginSet()
                    )
                ) {
                    importer.importWorkspace(it.project, projectDir, virtualFileUrlManager) {}
                }
            }
        }

        if (storage == null) {
            assertFalse((projectDir / "workspace.json").exists(), "Workspace import failed")
            return
        }

        val data = workspaceData(storage, projectDir)
        val workspaceJson = toJson(data)
        compareWithTestdata(projectDir / "workspace.json", cropJarPaths(workspaceJson))
        val restoredData = Json.decodeFromString<WorkspaceData>(workspaceJson)
        val restoredJson = toJson(restoredData)
        assertEquals(cropJarPaths(workspaceJson), cropJarPaths(restoredJson))
        val restoredStorage = workspaceModel(restoredData, projectDir, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true))
        val distilledData = workspaceData(restoredStorage, projectDir)
        assertEquals(data, distilledData)
    }

    fun cropJarPaths(jsonString: String): String =
        """"([^"]*?)/([^/"]*\.jar)"""".toRegex()
            .replace(jsonString) { """"${it.groupValues[2]}"""" }
}