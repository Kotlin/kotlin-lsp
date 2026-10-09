// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.filesystem.FileUrlList
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.ToolFileListener
import com.jetbrains.ls.imports.api.ToolFileWatchDispatcher
import com.jetbrains.ls.imports.api.ToolFileWatcher
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.gradle.GradleTool
import com.jetbrains.ls.imports.json.JsonTool
import com.jetbrains.ls.imports.maven.MavenTool
import com.jetbrains.ls.snapshot.api.impl.core.rocks.FileSystemChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The directories a build tool watches ([ToolFileWatcher]): the indexing roots are the source roots,
 * a build file sits beside them, so each tool names the directories of its inputs itself — at start,
 * from the committed model. The workspace side ([ToolFileWatchDispatcher]) registers them with the
 * real watcher and routes the events back to the tool.
 */
class ToolWatchRegistrationTest {
    private val urlManager = IdeVirtualFileUrlManagerImpl()

    private class RecordingWatcher : ToolFileWatcher {
        val directories = linkedSetOf<Path>()
        override fun watch(directory: Path, listener: ToolFileListener) {
            directories.add(directory)
        }
    }

    @Test
    fun `the Maven tool watches its settings directories and the pom directories of the model`() {
        val watcher = RecordingWatcher()
        val storage = model(externalSystem = "MAVEN", rootProjectPath = Path.of("/w/pom.xml").toString())
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
    fun `registrations before the bind are replayed, a directory is registered once`() {
        val dispatcher = ToolFileWatchDispatcher()
        val listener = recordingListener()
        dispatcher.watch(Path.of("/w/a"), listener)
        dispatcher.watch(Path.of("/w/a"), recordingListener())
        dispatcher.watch(Path.of("/w/b"), listener)

        val registered = mutableListOf<Path>()
        dispatcher.bind { registered.add(it) }
        dispatcher.watch(Path.of("/w/b"), recordingListener())
        dispatcher.watch(Path.of("/w/c"), listener)

        assertEquals(listOf(Path.of("/w/a"), Path.of("/w/b"), Path.of("/w/c")), registered)
    }

    @Test
    fun `repeating the same listener registers its directory once before and after bind`() {
        val dispatcher = ToolFileWatchDispatcher()
        val listener = recordingListener()
        val directory = Path.of("/w")
        dispatcher.watch(directory, listener)
        dispatcher.watch(directory, listener)
        val registered = mutableListOf<Path>()
        dispatcher.bind { registered.add(it) }
        dispatcher.watch(directory, listener)
        dispatcher.watch(directory, listener)
        assertEquals(listOf(directory), registered)
    }

    @Test
    fun `a change is routed to the listeners of its directory, lost changes to everyone`() {
        val dispatcher = ToolFileWatchDispatcher()
        dispatcher.bind { }
        val ofA = recordingListener()
        val ofB = recordingListener()
        dispatcher.watch(Path.of("/w/a"), ofA)
        dispatcher.watch(Path.of("/w/b"), ofB)

        dispatcher.dispatch(invalidate("/w/a/pom.xml"))
        dispatcher.dispatch(invalidate("/w/a"))
        dispatcher.dispatch(invalidate("/w/elsewhere/pom.xml"))
        assertEquals(listOf(Path.of("/w/a/pom.xml"), Path.of("/w/a")), ofA.changedPaths)
        assertEquals(emptyList<Path>(), ofB.changedPaths)

        dispatcher.dispatch(FileSystemChange.Rescan)
        assertEquals(1, ofA.lostCount)
        assertEquals(1, ofB.lostCount)
    }

    private class RecordingListener : ToolFileListener {
        val changedPaths = mutableListOf<Path>()
        var lostCount = 0
        override fun changed(path: Path) {
            changedPaths.add(path)
        }

        override fun lost() {
            lostCount++
        }
    }

    private fun recordingListener() = RecordingListener()

    private fun invalidate(path: String): FileSystemChange =
        FileSystemChange.Invalidate(FileUrlList.of(FileUrl.fromPath("file", path)))

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
