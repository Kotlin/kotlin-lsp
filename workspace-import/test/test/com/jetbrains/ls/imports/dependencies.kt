// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("RAW_RUN_BLOCKING")
package com.jetbrains.ls.imports

import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.intellij.build.dependencies.BuildDependenciesCommunityRoot
import org.jetbrains.intellij.build.dependencies.BuildDependenciesConstants
import org.jetbrains.intellij.build.dependencies.BuildDependenciesDownloader
import org.jetbrains.intellij.build.downloadFileToCacheLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val MAVEN_VERSION = "3.9.11" // 4.0.0-rc-5 (ok), 3.8.9 (ok), 3.6.3 (ok)
private const val GRADLE_VERSION = "9.2.0" // 8.14.3 (ok), 7.6.6 (fails)

private val mavenUnzipMutex = Mutex()
private val gradleUnzipMutex = Mutex()

fun downloadMavenBinaries(): Path {
    val uri = BuildDependenciesDownloader.getUriForMavenArtifact(
        mavenRepository = BuildDependenciesConstants.MAVEN_CENTRAL_URL,
        groupId = "org.apache.maven",
        artifactId = "apache-maven",
        version = MAVEN_VERSION,
        classifier = "bin",
        packaging = "zip"
    )

    val targetDir = downloadAndUnzip(uri.toString(), mavenUnzipMutex)
    return targetDir.resolve("apache-maven-$MAVEN_VERSION").also {
        require(it.isDirectory()) { "Expecting a directory: $it" }
    }
}

fun downloadGradleBinaries(): Path {
    val uri = "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    val targetDir = downloadAndUnzip(uri, gradleUnzipMutex)
    return targetDir.resolve("gradle-$GRADLE_VERSION").also {
        require(it.isDirectory()) { "Expecting a directory: $it" }
    }
}

private fun downloadAndUnzip(uri: String, unzipMutex: Mutex): Path =
    runBlocking(Dispatchers.IO) {
        val communityPath = Path.of(PathManager.getCommunityHomePath())
        val communityRoot = BuildDependenciesCommunityRoot(communityPath)
        val path = downloadFileToCacheLocation(uri, communityRoot)
        val targetDir = path.parent.resolve(path.name.removeSuffix(".zip"))
        unzipMutex.withLock {
            if (targetDir.exists()) {
                try {
                    // update file modification time to maintain FIFO caches, i.e., in a persistent cache dir on TeamCity agent
                    Files.setLastModifiedTime(targetDir, FileTime.from(Instant.now()))
                }
                catch (_: IOException) {
                }
            } else {
                BuildDependenciesDownloader.extractFile(path, targetDir, communityRoot)
            }
        }
        targetDir
    }
