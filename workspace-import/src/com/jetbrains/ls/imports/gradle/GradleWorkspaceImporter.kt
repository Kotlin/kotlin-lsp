// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.io.delete
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.findTheMostCompatibleJdk
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.getInitScriptPath
import com.jetbrains.ls.imports.gradle.action.ProjectMetadataBuilder
import com.jetbrains.ls.imports.gradle.util.GradleOutputStream
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = logger<GradleWorkspaceImporter>()

object GradleWorkspaceImporter : WorkspaceImporter {
    private const val IDEA_SYNC_ACTIVE_PROPERTY = "idea.sync.active"
    private const val KOTLIN_LSP_IMPORT_PROPERTY = "com.jetbrains.ls.imports.gradle"
    private val IMPORTER_PROPERTIES = mapOf(
        // This imitates how IntelliJ invokes gradle during sync.
        // Some builds/plugins depend on this property to configure their build for sync
        IDEA_SYNC_ACTIVE_PROPERTY to "true",
        // Since this is not actually IntelliJ, offer an alternative identification
        KOTLIN_LSP_IMPORT_PROPERTY to "true"
    )

    const val LSP_GRADLE_PROJECT_OFFLINE_PROPERTY: String = "com.jetbrains.ls.imports.gradle.offline"
    const val LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY: String = "com.jetbrains.ls.imports.gradle.gradleUserHome"
    const val LSP_GRADLE_PROJECT_SELF_CONTAINED_INIT_SCRIPT: String = "com.jetbrains.ls.imports.gradle.selfContainedInitScript"
    const val LSP_GRADLE_PROJECT_SELF_CONTAINED_PROXY_URL_PROPERTY: String = "com.jetbrains.ls.imports.gradle.selfContainedProxyUrl"

    private fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        ).any { (projectDirectory / it).exists() }
    }

    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Gradle project from: $projectDirectory")

        val gradleProjectData = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .also { gradleConnector ->
                System.getProperty(LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY)?.let {
                    if (it.isNotBlank()) {
                        val gradleUserHome = Path.of(it)
                        check(gradleUserHome.exists())
                        gradleConnector.useGradleUserHomeDir(gradleUserHome.toFile())
                    }

                }
            }
            .connect()
            .use {
                val initScriptPath = getInitScriptPath()
                try {
                    val builder = it.action(ProjectMetadataBuilder())
                        .addArguments("--stacktrace", "--init-script", initScriptPath.absolutePathString())
                        .withSystemProperties(IMPORTER_PROPERTIES)
                        .also { builder ->
                        if (System.getProperty(LSP_GRADLE_PROJECT_OFFLINE_PROPERTY)?.toBoolean() == true) {
                            System.getProperty(LSP_GRADLE_PROJECT_SELF_CONTAINED_INIT_SCRIPT)?.let { initScript ->
                                builder.addArguments(
                                    "--init-script",
                                    Path.of(initScript).toString()
                                )
                            }?.setEnvironmentVariables(
                                mapOf(
                                    "SELF_CONTAINED_PROXY_URL" to System.getProperty(LSP_GRADLE_PROJECT_SELF_CONTAINED_PROXY_URL_PROPERTY),
                                    "GRADLE_USER_HOME" to System.getProperty(LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY)
                                )
                            )
                        }
                    }
                    .setStandardOutput(GradleOutputStream { line -> progress.onStdOutput(line) })
                    .setStandardError(GradleOutputStream { line -> progress.onErrorOutput(line) })
                    .addProgressListener(
                        GradleProgressListener { line -> progress.onStdOutput(line) },
                        setOf(OperationType.GENERIC, OperationType.FILE_DOWNLOAD, OperationType.PROJECT_CONFIGURATION)
                    )
                    .withCancellationToken(GradleConnector.newCancellationTokenSource().token())
                    val jdkToUse = findTheMostCompatibleJdk(project, projectDirectory)
                    if (jdkToUse != null) {
                        builder.setJavaHome(File(jdkToUse))
                    }
                    builder.run()
                } finally {
                    initScriptPath.delete()
                }
            }
        val entitySource = WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager))
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    IdeaProjectMapper().toWorkspaceData(gradleProjectData),
                    projectDirectory,
                    progress
                ),
                projectDirectory,
                entitySource,
                virtualFileUrlManager,
                ignoreDuplicateLibsAndSdks = true,
            )
            fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
        }
    }

    private class GradleProgressListener(private val lineConsumer: (line: String) -> Unit) : org.gradle.tooling.events.ProgressListener {
        override fun statusChanged(event: ProgressEvent) {
            lineConsumer(event.displayName)
        }
    }
}
