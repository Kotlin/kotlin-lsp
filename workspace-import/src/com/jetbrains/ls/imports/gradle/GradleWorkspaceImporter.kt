// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.*
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaContentRoot
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = logger<GradleWorkspaceImporter>()

private const val JB_GRADLE_HOME: String = "JB_GRADLE_HOME"
private const val JB_GRADLE_JAVA_HOME: String = "JB_GRADLE_JAVA_HOME"

object GradleWorkspaceImporter : WorkspaceImporter {

    fun useGradleAndJava(mavenHome: Path, javaHome: Path) {
        System.setProperty(JB_GRADLE_HOME, mavenHome.toString())
        System.setProperty(JB_GRADLE_JAVA_HOME, javaHome.toString())
    }

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
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Gradle project from: $projectDirectory")

        val projectModel = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())
            .connect()
            .use {
                it.model(IdeaProject::class.java)
                    .addArguments("--stacktrace")
                    .withCancellationToken(GradleConnector.newCancellationTokenSource().token())
                    .get()
            }
        val entitySource = WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager))
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    IdeaProjectMapper.toWorkspaceData(projectModel),
                    projectDirectory,
                    onUnresolvedDependency
                ),
                projectDirectory,
                entitySource,
                virtualFileUrlManager,
                ignoreDuplicateLibsAndSdks = true,
            )
        }
    }
}
