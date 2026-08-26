// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkRootTypeId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.workspaceModel.ide.impl.createIdeVirtualFileUrlManager
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.api.withProject
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.test.api.utils.testPluginSet
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.time.Duration.Companion.minutes

/**
 * SDK class roots keep the shape that `JavaSdkImpl.findClasses` emits. The analyzer's jrt
 * file system mounts at `/modules`, so a modular JDK gives `jrt://<home>!/<module>` roots
 * with no `!/modules/` segment (the IntelliJ shape). The `jar://…/jre/lib/rt.jar!/` roots of
 * a non-modular JDK must pass through unchanged. LSP-1693: a rewrite once inserted `modules/`
 * into those jar roots. The JDK 8 classpath became a set of dangling URLs, and `java.lang`
 * was unresolvable in every imported module.
 */
class JdkSdkRootsTest {
    @Test
    fun nonModularJdkClassRootsAreNotRewrittenToJrtModules(@TempDir workspacePath: Path, @TempDir jdkHome: Path) {
        // Minimal JDK 8 shape: class roots are scanned from jre/lib/*.jar, and the missing
        // lib/jrt-fs.jar marks the runtime as non-modular.
        val rtJar = jdkHome.resolve("jre").resolve("lib").resolve("rt.jar")
        Files.createDirectories(rtJar.parent)
        JarOutputStream(Files.newOutputStream(rtJar)).close()

        val classRoots = importJdkClassRoots(workspacePath, jdkHome)
        assertTrue(
            classRoots.isNotEmpty() && classRoots.all { it.startsWith("jar://") && it.endsWith("!/") && !it.contains("!/modules/") },
            "non-modular JDK class roots must stay jar roots: $classRoots",
        )
    }

    @Test
    fun modularJdkClassRootsKeepThePlainJrtShape(@TempDir workspacePath: Path) {
        // The JDK running the test is modular.
        val classRoots = importJdkClassRoots(workspacePath, Path.of(System.getProperty("java.home")))
        assertTrue(
            classRoots.isNotEmpty() && classRoots.all { it.startsWith("jrt://") && !it.contains("!/modules/") },
            "modular JDK class roots must keep the plain jrt shape: $classRoots",
        )
    }

    // The analyzer application (not the IJ test application) provides the VFS bits behind
    // JavaSdkImpl.findClasses: this module's tests share one JVM with AnalyzerEDTExtension's
    // fake EDT, which a @TestApplication bootstrap would poison for every other class. The
    // application services resolve only inside withProject, which binds the analyzer context.
    private fun importJdkClassRoots(workspacePath: Path, jdkHome: Path): List<String> {
        val data = WorkspaceData(
            sdks = listOf(SdkData(name = "jdk", type = "JavaSDK", version = null, homePath = jdkHome.toString(), additionalData = "")),
        )
        return timeoutRunBlocking(timeout = 10.minutes) {
            withAnalyzer(isUnitTestMode = true) { analyzer ->
                val currentSnapshot = WorkspaceModelSnapshot.empty()
                analyzer.withProject(
                    analyzerProjectConfigForImport(
                        projectId = AnalyzerProjectId(),
                        entities = currentSnapshot.entityStore,
                        urlManager = currentSnapshot.virtualFileUrlManager,
                        pluginSet = testPluginSet,
                    )
                ) {
                    val storage = MutableEntityStorage.create()
                    storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))
                    storage.entities<SdkEntity>().single().roots
                        .filter { it.type == SdkRootTypeId.CLASSES }
                        .map { it.url.url }
                }
            }
        }
    }
}
