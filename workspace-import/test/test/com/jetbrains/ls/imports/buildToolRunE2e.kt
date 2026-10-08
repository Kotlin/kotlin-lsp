// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.openapi.application.PathManager
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.api.withProject
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriver
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.test.api.utils.testPluginSet
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.fail
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

/**
 * The shared half of a run end-to-end test: a real import that keeps the tool alive, the event collection of
 * one [BuildTool.run], and the fixture copy a build may write into. Each build tool has its own test class on
 * top of these.
 */

/** Imports the project and hands the live tool with the imported model to [body]; closing follows the body. */
internal fun withLiveTool(
    driver: BuildToolDriver,
    parameters: WorkspaceImportParameters,
    body: suspend (BuildTool, EntityStorage) -> Unit,
) = timeoutRunBlocking(timeout = 15.minutes) {
    withAnalyzer(isUnitTestMode = true) { analyzer ->
        val snapshot = WorkspaceModelSnapshot.empty()
        val urlManager = snapshot.virtualFileUrlManager
        analyzer.withProject(
            analyzerProjectConfigForImport(
                projectId = AnalyzerProjectId(),
                entities = snapshot.entityStore,
                urlManager = urlManager,
                pluginSet = testPluginSet,
            )
        ) { handle ->
            val toolContext = BuildToolDriverContext(
                fileChanges = MutableSharedFlow(),
                entityStorage = { MutableEntityStorage.create() },
            )
            driver.start(toolContext, parameters).use { tool ->
                val reporter = LoggingWorkspaceProgressReporter()
                var storage: EntityStorage? = null
                tool.sync(BuildToolContext(handle.project, urlManager), SyncRequest(targetWatermark = 0, changes = null, toolRequest = null)).collect { event ->
                    when (event) {
                        is ImportEvent.UpdateWorkspaceModel -> storage = event.storage
                        is ImportEvent.Failed -> throw AssertionError(
                            "Import failed: ${event.cause.message}\n---- tool output ----\n${reporter.capturedOutput}",
                            event.cause,
                        )
                        is ImportEvent.StdOutput -> reporter.onStdOutput(event.line)
                        is ImportEvent.ErrorOutput -> reporter.onErrorOutput(event.line)
                        is ImportEvent.ProgressStatus -> reporter.progressStatus(event.text)
                        is ImportEvent.UnresolvedDependency -> reporter.onUnresolvedDependency(event.depName)
                    }
                }
                body(tool, storage ?: fail("The import published no model:\n${reporter.capturedOutput}"))
            }
        }
    }
}

internal suspend fun BuildTool.collectRun(request: RunRequest): List<RunTaskEvent> =
    run(request).use { handle -> buildList { handle.events.collect(::add) } }

internal fun EntityStorage.moduleNamed(name: String): ModuleEntity =
    entities(ModuleEntity::class.java).firstOrNull { it.name == name }
        ?: fail("No module '$name'; the import produced: ${entities(ModuleEntity::class.java).map { it.name }.toList()}")

internal fun List<RunTaskEvent>.assertFinishedWithZero() =
    assertEquals(RunTaskEvent.Finished(0), lastOrNull()) { "expected a clean exit, got:\n${joinToString("\n")}" }

/** A temp copy of an import fixture: a build writes `build/` or `target/` and must not touch the repository. */
@OptIn(ExperimentalPathApi::class)
internal fun copyRunFixture(relative: String): Path {
    val source = PathManager.getHomeDir() / "language-server" / "community" / "workspace-import" / "test" / "testData" / relative
    require(source.exists()) { "Fixture not found: $source" }
    val target = createTempDirectory("run-e2e-") / source.fileName.toString()
    source.copyToRecursively(target, followLinks = false, overwrite = false)
    return target
}

internal fun withSystemProperties(vararg properties: Pair<String, String>, action: () -> Unit) {
    val previous = properties.map { (key, _) -> key to System.getProperty(key) }
    properties.forEach { (key, value) -> System.setProperty(key, value) }
    try {
        action()
    } finally {
        previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }
}
