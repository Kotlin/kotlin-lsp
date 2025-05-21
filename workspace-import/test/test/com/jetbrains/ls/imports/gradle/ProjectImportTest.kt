package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.application.PathManager
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.imports.json.workspaceModel
import com.jetbrains.ls.snapshot.api.impl.core.createServerStarterAnalyzerImpl
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.div
import kotlin.io.path.exists

class ProjectImportTest {
    private val testdataDir = Paths.get(PathManager.getHomePath(), "language-server", "community", "workspace-import", "test", "testData", "gradle")
    private val importer = GradleWorkspaceImporter

    @Test
    fun petClinic() {
        doTest("PetClinic")
    }

    @Test
    fun multiProjectKotlinDSL() {
        doTest("MultiProjectKotlinDSL")
    }

    @Test
    fun multiProjectGroovyDSL() {
        doTest("MultiProjectGroovyDSL")
    }

    @Test
    fun customSourceSetts() {
        doTest("CustomSourceSets")
    }

    @Test
    fun dependencies() {
        doTest("Dependencies")
    }

    @Test
    fun empty() {
        doTest("Empty")
    }

    @Test
    fun nonExistentDependency() {
        // TODO: Check that missing dependencies are reported
        doTest("NonExistentDependency")
    }


    private fun doTest(project: String) {
        val projectDir = testdataDir / project
        require(projectDir.exists()) { "Project $project does not exist" }

        val storage = performImport(projectDir)

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


    private fun performImport(projectDir: Path): MutableEntityStorage? {
        val storageRef = AtomicReference<MutableEntityStorage?>(null)
        runBlocking(Dispatchers.Default) {
            // to be able to access services registered in analyzer.xml during import
            createServerStarterAnalyzerImpl(emptyList(), isUnitTestMode = true).start {
                storageRef.set(importer.tryImportWorkspace(projectDir, IdeVirtualFileUrlManagerImpl(true), {}))
            }
        }

        return storageRef.get()
    }

    fun cropJarPaths(jsonString: String): String =
        """"([^"]*?)/([^/"]*\.jar)"""".toRegex()
            .replace(jsonString) { """"${it.groupValues[2]}"""" }
}