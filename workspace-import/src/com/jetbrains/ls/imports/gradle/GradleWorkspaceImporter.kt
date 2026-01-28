// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter.postProcessWorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.util.GradleVersion
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val LOG = logger<GradleWorkspaceImporter>()

object GradleWorkspaceImporter : WorkspaceImporter {

    private const val JAVA_11: Int = 11
    private const val JAVA_17: Int = 17

    private val GRADLE_8_14: GradleVersion = GradleVersion.version("8.14")
    private val GRADLE_7_3: GradleVersion = GradleVersion.version("7.3")
    private val GRADLE_5_0: GradleVersion = GradleVersion.version("5.0")

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
                val builder = it.model(IdeaProject::class.java)
                    .addArguments("--stacktrace")
                    .withCancellationToken(GradleConnector.newCancellationTokenSource().token())
                val jdkToUse = findTheMostCompatibleJdk(project, projectDirectory)
                if (jdkToUse != null) {
                    builder.setJavaHome(File(jdkToUse))
                }
                builder.get()
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

    private fun findTheMostCompatibleJdk(project: Project, projectDirectory: Path): String? {
        val gradleVersion = guessGradleVersion(projectDirectory) ?: return null
        val javaSdkType = SimpleJavaSdkType.getInstance()
        val suggestedJavaPath = JavaHomeFinder.suggestHomePaths(project)
            .map { Pair(JavaVersion.tryParse(javaSdkType.getVersionString(it)), it) }
            .filter { it.first != null }
            .sortedByDescending { it.first }
            .first { gradleVersion.isCompatibleWithJava(it.first) }
            .second
        LOG.info("Gradle Tooling API will use Java located in $suggestedJavaPath")
        return suggestedJavaPath
    }

    private fun GradleVersion.isCompatibleWithJava(javaVersion: JavaVersion?): Boolean {
        if (javaVersion == null || !javaVersion.isAtLeast(8)) {
            return false
        }
        if (compareTo(GRADLE_5_0) <= 0 && javaVersion.feature <= JAVA_11) {
            return true
        }
        if (compareTo(GRADLE_8_14) < 0 && javaVersion.feature < JAVA_17) {
            return false
        }
        if (compareTo(GRADLE_7_3) >= 0 && javaVersion.feature == JAVA_17) {
            return true
        }
        return false
    }

    private fun guessGradleVersion(projectDirectory: Path): GradleVersion? {
        val propertiesPath = findWrapperProperties(projectDirectory) ?: return null
        val properties = readGradleProperties(propertiesPath) ?: return null
        val url: URI = properties["distributionUrl"]
            .let {
                return@let try {
                    URI.create(it as String)
                } catch (_: Exception) {
                    null
                }
            } ?: return null
        val versionString = url.path.split("/")
            .last()
            .removeSuffix("-bin.zip")
            .removeSuffix("-all.zip")
            .removePrefix("gradle-")
        return try {
            GradleVersion.version(versionString)
        } catch (_: Exception) {
            return null
        }
    }

    private fun findWrapperProperties(root: Path): Path? {
        val gradleDir = if (root.isRegularFile()) root.resolveSibling("gradle") else root.resolve("gradle")
        if (!gradleDir.isDirectory()) {
            return null
        }
        val wrapperDir = gradleDir.resolve("wrapper")
        if (!wrapperDir.isDirectory()) {
            return null
        }
        try {
            Files.list(wrapperDir)
                .use { pathsStream ->
                    val candidates = pathsStream
                        .filter {
                            FileUtilRt.extensionEquals(it.fileName.toString(), "properties") && it.isRegularFile()
                        }
                        .toList()
                    if (candidates.size != 1) {
                        return null
                    }
                    return candidates.first()
                }
        } catch (_: Exception) {
            return null
        }
    }

    private fun readGradleProperties(propertiesFile: Path): Properties? {
        return try {
            Files.newBufferedReader(propertiesFile, StandardCharsets.ISO_8859_1)
                .use { reader -> Properties().apply { load(reader) } }
        } catch (_: Exception) {
            null
        }
    }
}
