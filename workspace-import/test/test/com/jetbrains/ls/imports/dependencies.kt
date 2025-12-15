// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.dependencies.BuildDependenciesCommunityRoot
import org.jetbrains.intellij.build.dependencies.BuildDependenciesConstants
import org.jetbrains.intellij.build.dependencies.BuildDependenciesDownloader
import org.jetbrains.intellij.build.downloadFileToCacheLocation
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val MAVEN_VERSION = "3.9.11"

@Suppress("RAW_RUN_BLOCKING")
fun downloadMavenBinaries(communityPath: Path): Path = runBlocking(Dispatchers.IO) {
    val communityRoot = BuildDependenciesCommunityRoot(communityPath)
    val uri = BuildDependenciesDownloader.getUriForMavenArtifact(
        mavenRepository = BuildDependenciesConstants.MAVEN_CENTRAL_URL,
        groupId = "org.apache.maven",
        artifactId = "apache-maven",
        version = MAVEN_VERSION,
        classifier = "bin",
        packaging = "zip"
    )
    val path = downloadFileToCacheLocation(uri.toString(), communityRoot)
    val targetDir = path.parent.resolve(path.name.removeSuffix(".zip"))
    BuildDependenciesDownloader.extractFile(path, targetDir, communityRoot)
    val mavenHome = targetDir.resolve("apache-maven-$MAVEN_VERSION")
    require(mavenHome.isDirectory()) { "Expecting a directory: $mavenHome" }
    mavenHome
}