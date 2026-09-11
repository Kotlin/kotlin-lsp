// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.projectRoots.testFramework.TestJdkAnnotationsFilesProvider
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.workspace.jps.entities.SdkRoot
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.api.core.util.ANNOTATIONS_SDK_ROOT_TYPE
import com.intellij.util.BazelEnvironmentUtil
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val LOG = fileLogger()

/** The descriptor at the resource root of [the module that holds the annotations][ANNOTATIONS_DIRECTORY]. */
private const val ANNOTATIONS_MODULE_DESCRIPTOR = "intellij.java.jdkAnnotations.xml"

/** The resources of the `intellij.java.jdkAnnotations` module, relative to the community home directory. */
private const val ANNOTATIONS_DIRECTORY = "java/jdkAnnotations/resources"

/** An anchor to reach the classloader of this module, which sees the annotations module. */
private object ModuleAnchor

/**
 * The external annotations of the JDK, as SDK roots.
 *
 * IntelliJ IDEA adds this root with `JavaSdkImpl.attachJdkAnnotations`, which needs an `SdkModificator`.
 * The language server writes the SDK entity into the workspace model directly, so it adds the root itself.
 * Without the root the JDK has no external annotations.
 *
 * The result is empty when no annotations root exists.
 */
fun jdkAnnotationsSdkRoots(virtualFileUrlManager: VirtualFileUrlManager): List<SdkRoot> {
    val checked = mutableListOf<String>()
    for (candidate in jdkAnnotationsCandidates()) {
        val url = candidate.toAnnotationsRootUrl()
        if (url == null) {
            checked.add("$candidate")
            continue
        }
        LOG.info("The JDK external annotations root is $url")
        return listOf(SdkRoot(virtualFileUrlManager.getOrCreateFromUrl(url), ANNOTATIONS_SDK_ROOT_TYPE))
    }
    LOG.warn("The JDK has no external annotations. Paths checked: $checked")
    return emptyList()
}

/**
 * The `intellij.java.jdkAnnotations` content module holds the annotations as resources.
 * The classloader finds the module jar in a distribution and the module output directory in a run from sources.
 * `JavaSdkImpl.internalJdkAnnotationsPath` is unusable here, because it calls `LocalFileSystem.getInstance()`,
 * and the local file system of the analyzer is an `AnalyzerFileVirtualFileSystem`.
 */
private fun jdkAnnotationsCandidates(): List<Path> = buildList {
    PathManager.getResourceRoot(ModuleAnchor::class.java.classLoader, ANNOTATIONS_MODULE_DESCRIPTOR)
        ?.let { add(Path.of(it)) }
    // A source checkout.
    add(Path.of(PathManager.getCommunityHomePath(), ANNOTATIONS_DIRECTORY))
    // A test under Bazel, where the home directory above is a temporary one and holds no annotations.
    // A service reports the location, because the runfiles tree has no fixed path.
    if (BazelEnvironmentUtil.isBazelTestRun()) {
        ServiceLoader
            .load(TestJdkAnnotationsFilesProvider::class.java, TestJdkAnnotationsFilesProvider::class.java.classLoader)
            .firstOrNull()
            ?.jdkAnnotationsPath
            ?.let { add(it) }
    }
}

private fun Path.toAnnotationsRootUrl(): String? {
    val path = FileUtilRt.toSystemIndependentName(toString())
    return when {
        isRegularFile() -> "jar://$path!/"
        isDirectory() -> "file://$path"
        else -> null
    }
}
