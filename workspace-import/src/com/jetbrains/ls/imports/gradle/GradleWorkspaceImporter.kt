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
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.findTheMostCompatibleJdk
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.getInitScriptPath
import com.jetbrains.ls.imports.gradle.action.ProjectMetadataBuilder
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import org.gradle.tooling.GradleConnector
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = logger<GradleWorkspaceImporter>()

object GradleWorkspaceImporter : WorkspaceImporter {

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
        onUnresolvedDependency: (String) -> Unit,
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Gradle project from: $projectDirectory")

        val gradleProjectData = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .connect()
            .use {
                val initScriptPath = getInitScriptPath()
                try {
                    val builder = it.action(ProjectMetadataBuilder())
                        .addArguments("--stacktrace", "--init-script", initScriptPath.absolutePathString())
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
                    IdeaProjectMapper.toWorkspaceData(gradleProjectData),
                    projectDirectory,
                    onUnresolvedDependency
                ),
                projectDirectory,
                entitySource,
                virtualFileUrlManager,
                ignoreDuplicateLibsAndSdks = true,
            )
            fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
        }
    }
}
