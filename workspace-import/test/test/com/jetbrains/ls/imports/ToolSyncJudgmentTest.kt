// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.api.withProject
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.test.api.utils.testPluginSet
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.gradle.GradleTool
import com.jetbrains.ls.imports.json.JsonTool
import com.jetbrains.ls.imports.jps.JpsBuildTool
import com.jetbrains.ls.imports.maven.MavenTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * The sync-time judgment of the build tools: a sync the tool did not ask for and that is not forced
 * lets the tool verify its inputs on disk — against the committed model and the wall-clock baseline
 * of its last import — and answer "the model is current". Disk is the source, not the tool's own
 * event queue, so the answer covers everything written before the sync.
 */
class ToolSyncJudgmentTest {
    private val urlManager = IdeVirtualFileUrlManagerImpl()

    @Test
    fun `a cancelled sync cannot vouch for the old model`(@TempDir workspace: Path) = timeoutRunBlocking {
        val script = workspace / "build.gradle"
        script.writeText("plugins {}")
        agedByAnHour(script)
        val storage = model("GRADLE", workspace.toString(), workspace.toString())
        (workspace / ".idea").createDirectories().resolve("modules.xml").writeText("<project/>")
        val tools = listOf(
            GradleTool(toolContext(storage), parameters(workspace)),
            MavenTool(toolContext(), parameters(workspace)),
            JsonTool(toolContext(), parameters(workspace)),
            JpsBuildTool(toolContext(), parameters(workspace)),
        )
        for (tool in tools) {
            val request = SyncRequest(targetWatermark = 1, force = true, toolRequest = null)
            tool.sync(BuildToolContext({ error("Import started") }, urlManager), request).toList()
            val cancelledContext = BuildToolContext({ throw CancellationException("Import cancelled") }, urlManager)
            val cancelled = runCatching { tool.sync(cancelledContext, request).toList() }
            assertInstanceOf(CancellationException::class.java, cancelled.exceptionOrNull(), "${tool.javaClass.simpleName}: ${cancelled.getOrNull()}")
            val events = tool.sync(BuildToolContext({ error("Import retried") }, urlManager), request.copy(force = false)).toList()
            assertFalse(events.any { it is ImportEvent.WorkspaceModelNotChanged }, tool.javaClass.simpleName)
        }
    }

    @Test
    fun `Gradle detects a deleted script without importing for absent alternatives`(@TempDir workspace: Path) {
        val script = workspace / "build.gradle"
        script.writeText("plugins {}")
        agedByAnHour(script)
        val tool = GradleTool(toolContext(model("GRADLE", workspace.toString(), workspace.toString())), parameters(workspace))
        assertTrue(syncRuns(tool, force = true))
        assertFalse(syncRuns(tool, force = false))
        Files.delete(script)
        assertTrue(syncRuns(tool, force = false), "the imported script was deleted")
        assertFalse(syncRuns(tool, force = false), "the failed attempt covered the deletion")
    }

    @Test
    fun `Gradle detects a script deleted while the import runs`(@TempDir workspace: Path) = timeoutRunBlocking {
        val script = workspace / "build.gradle"
        script.writeText("plugins {}")
        agedByAnHour(script)
        val tool = GradleTool(toolContext(model("GRADLE", workspace.toString(), workspace.toString())), parameters(workspace))
        val context = BuildToolContext({
            Files.delete(script)
            error("Import failed after the script was deleted")
        }, urlManager)
        tool.sync(context, SyncRequest(1, force = true, toolRequest = null)).toList()
        assertTrue(syncRuns(tool, force = false), "the next sync must cover the deletion")
    }

    @Test
    fun `Maven detects a deleted settings file`(@TempDir workspace: Path) {
        val pom = workspace / "pom.xml"
        pom.writeText("<project/>")
        agedByAnHour(pom)
        val settings = (workspace / ".mvn").createDirectories() / "maven.config"
        settings.writeText("-T4")
        agedByAnHour(settings)
        val tool = MavenTool(toolContext(), parameters(workspace))
        assertTrue(syncRuns(tool, force = true))
        assertFalse(syncRuns(tool, force = false))
        Files.delete(settings)
        assertTrue(syncRuns(tool, force = false))
        assertFalse(syncRuns(tool, force = false), "absent optional settings do not cause repeated imports")
    }

    @Test
    fun `JPS detects deleted inputs of a linked Gradle project`(@TempDir workspace: Path) = timeoutRunBlocking {
        val linked = (workspace / "linked").createDirectories()
        val module = (linked / "sub").createDirectories()
        val script = module / "build.gradle"
        script.writeText("plugins {}")
        agedByAnHour(script)
        (workspace / ".idea").createDirectories().resolve("gradle.xml").writeText(
            """<project><component name="GradleSettings"><option name="linkedExternalProjectsSettings">
                <GradleProjectSettings><option name="externalProjectPath" value="$linked"/></GradleProjectSettings>
                </option></component></project>""",
        )
        (workspace / ".idea" / "modules.xml").writeText("<project/>")
        val tool = JpsBuildTool(
            toolContext(model("GRADLE", linked.toString(), module.toString())),
            parameters(workspace).copy(defaultSdkPath = Path.of(System.getProperty("java.home"))),
        )
        withAnalyzer(isUnitTestMode = true) { analyzer ->
            val snapshot = WorkspaceModelSnapshot.empty()
            analyzer.withProject(analyzerProjectConfigForImport(
                projectId = AnalyzerProjectId(),
                entities = snapshot.entityStore,
                urlManager = snapshot.virtualFileUrlManager,
                pluginSet = testPluginSet,
            )) { context ->
                val events = tool.sync(BuildToolContext(context.project, urlManager), SyncRequest(1, force = true, toolRequest = null)).toList()
                assertTrue(events.any { it is ImportEvent.UpdateWorkspaceModel }, events.toString())
            }
        }
        assertFalse(syncRuns(tool, force = false))
        Files.delete(script)
        assertTrue(syncRuns(tool, force = false))
    }

    @Test
    fun `Maven re-imports when a pom of the model is touched, missing, or the tool never synced`(@TempDir workspace: Path) {
        val pom = workspace / "pom.xml"
        val storage = model(externalSystem = "MAVEN", rootProjectPath = pom.toString(), moduleDir = workspace.toString())
        val tool = MavenTool(toolContext(storage), parameters(workspace))

        assertTrue(syncRuns(tool, force = false), "a tool that never synced has nothing to vouch with")
        assertTrue(syncRuns(tool, force = true), "the forced sync sets the baseline")
        assertTrue(syncRuns(tool, force = false), "the pom the model lists is missing on disk")

        pom.writeText("<project/>")
        agedByAnHour(pom)
        assertFalse(syncRuns(tool, force = false), "the pom is older than the baseline")

        pom.writeText("<project><!-- edited --></project>")
        assertTrue(syncRuns(tool, force = false), "the pom changed after the baseline")
    }

    @Test
    fun `Maven re-imports when a settings file changes`(@TempDir workspace: Path) {
        val pom = workspace / "pom.xml"
        val storage = model(externalSystem = "MAVEN", rootProjectPath = pom.toString(), moduleDir = workspace.toString())
        val tool = MavenTool(toolContext(storage), parameters(workspace))

        assertTrue(syncRuns(tool, force = true), "the forced sync sets the baseline")
        pom.writeText("<project/>")
        agedByAnHour(pom)
        assertFalse(syncRuns(tool, force = false), "the aged pom reads as unchanged")

        (workspace / ".mvn").createDirectories()
        (workspace / ".mvn" / "maven.config").writeText("-T4")
        assertTrue(syncRuns(tool, force = false), "a settings file appeared after the baseline")
    }

    @Test
    fun `Gradle re-imports when a build script of the model changes`(@TempDir workspace: Path) {
        val module = (workspace / "sub").createDirectories()
        val script = module / "build.gradle"
        script.writeText("plugins {}")
        agedByAnHour(script)
        val storage = model(externalSystem = "GRADLE", rootProjectPath = workspace.toString(), moduleDir = module.toString())
        val tool = GradleTool(toolContext(storage), parameters(workspace))

        assertTrue(syncRuns(tool, force = true))
        assertFalse(syncRuns(tool, force = false), "nothing changed since the baseline")

        script.writeText("plugins { id 'java' }")
        assertTrue(syncRuns(tool, force = false), "the build script changed after the baseline")
    }

    @Test
    fun `JSON re-imports when its workspace file appears, changes, or disappears`(@TempDir workspace: Path) {
        val tool = JsonTool(toolContext(), parameters(workspace))
        assertTrue(syncRuns(tool, force = true), "the forced sync sets the baseline over the absent file")
        assertFalse(syncRuns(tool, force = false), "still no workspace.json")

        (workspace / "workspace.json").writeText("{}")
        assertTrue(syncRuns(tool, force = false), "the one input appeared")
    }

    private fun agedByAnHour(file: Path) {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3_600)))
    }

    /**
     * Whether the sync runs the import instead of answering "the model is current". The fixtures hold no
     * runnable build, so a running Maven import ends at the missing pom and a Gradle one at the thrown
     * analyzer-project access; only the short-circuit matters.
     */
    private fun syncRuns(tool: BuildTool, force: Boolean): Boolean = runBlocking {
        val context = BuildToolContext(
            project = { error("the sync started the import") },
            virtualFileUrlManager = urlManager,
        )
        val events = tool.sync(context, SyncRequest(targetWatermark = 1, force = force, toolRequest = null)).toList()
        events.none { it is ImportEvent.WorkspaceModelNotChanged }
    }

    private fun parameters(projectDirectory: Path) = WorkspaceImportParameters(
        projectFileOrDirectory = projectDirectory,
        defaultSdkPath = null,
    )

    private fun toolContext(storage: EntityStorage = MutableEntityStorage.create().toSnapshot()) =
        BuildToolDriverContext(entityStorage = { storage })

    /** One module of [externalSystem] at [moduleDir], the shape the tools read their inputs from. */
    private fun model(externalSystem: String, rootProjectPath: String, moduleDir: String): EntityStorage {
        val source = WorkspaceEntitySource(urlManager.storeAndGet("file:///w"))
        return MutableEntityStorage.create().apply {
            addEntity(ModuleEntity("m", emptyList(), source) {
                exModuleOptions = ExternalSystemModuleOptionsEntity(source) {
                    this.externalSystem = externalSystem
                    this.rootProjectPath = rootProjectPath
                    this.linkedProjectPath = moduleDir
                }
            })
        }.toSnapshot()
    }
}
