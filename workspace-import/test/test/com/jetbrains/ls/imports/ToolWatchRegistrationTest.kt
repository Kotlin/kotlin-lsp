// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.LateBoundToolFileWatcher
import com.jetbrains.ls.imports.api.ToolFileWatcher
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.gradle.GradleTool
import com.jetbrains.ls.imports.json.JsonTool
import com.jetbrains.ls.imports.maven.MavenTool
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The directories a build tool registers with the workspace file watcher ([ToolFileWatcher]): the indexing
 * roots are the source roots, a build file sits beside them, so each tool names the directories of its inputs
 * itself — at start, from the committed model.
 */
class ToolWatchRegistrationTest {
    private val urlManager = IdeVirtualFileUrlManagerImpl()

    private class RecordingWatcher : ToolFileWatcher {
        val directories = linkedSetOf<Path>()
        override fun watch(directory: Path) {
            directories.add(directory)
        }
    }

    @Test
    fun `the Maven tool watches its settings directories and the pom directories of the model`() {
        val watcher = RecordingWatcher()
        val storage = model(externalSystem = "MAVEN", rootProjectPath = "/w/pom.xml")
        MavenTool(toolContext(watcher, storage), parameters())
        assertEquals(
            paths("/w", "/w/.mvn", "/w/.mvn/wrapper", "/w/sub"),
            watcher.directories,
        )
    }

    @Test
    fun `the Gradle tool watches its settings directories and the build script directories of the model`() {
        val watcher = RecordingWatcher()
        val storage = model(externalSystem = "GRADLE", rootProjectPath = "/w")
        GradleTool(toolContext(watcher, storage), parameters())
        assertEquals(
            paths("/w", "/w/gradle/wrapper", "/w/sub", "/w/gradle"),
            watcher.directories,
        )
    }

    @Test
    fun `the JSON tool watches the project directory`() {
        val watcher = RecordingWatcher()
        JsonTool(toolContext(watcher), parameters())
        assertEquals(paths("/w"), watcher.directories)
    }

    @Test
    fun `registrations before the bind are replayed, duplicates are forwarded once`() {
        val watcher = LateBoundToolFileWatcher()
        watcher.watch(Path.of("/w/a"))
        watcher.watch(Path.of("/w/a"))
        watcher.watch(Path.of("/w/b"))

        val target = RecordingWatcher()
        val forwarded = mutableListOf<Path>()
        watcher.bind { directory ->
            forwarded.add(directory)
            target.watch(directory)
        }
        watcher.watch(Path.of("/w/b"))
        watcher.watch(Path.of("/w/c"))

        assertEquals(paths("/w/a", "/w/b", "/w/c"), target.directories)
        assertEquals(listOf(Path.of("/w/a"), Path.of("/w/b"), Path.of("/w/c")), forwarded)
    }

    private fun paths(vararg paths: String): Set<Path> = paths.mapTo(linkedSetOf(), Path::of)

    /** Every case imports the one project at `/w`. */
    private fun parameters() = WorkspaceImportParameters(
        projectFileOrDirectory = Path.of("/w"),
        defaultSdkPath = null,
    )

    private fun toolContext(
        watcher: ToolFileWatcher,
        storage: EntityStorage = MutableEntityStorage.create().toSnapshot(),
    ) = BuildToolDriverContext(
        fileChanges = MutableSharedFlow(),
        entityStorage = { storage },
        watcher = watcher,
    )

    /** One module of [externalSystem] at `/w/sub`, the shape [MavenTool] and [GradleTool] read their inputs from. */
    private fun model(externalSystem: String, rootProjectPath: String, moduleDir: String = "/w/sub"): EntityStorage {
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
