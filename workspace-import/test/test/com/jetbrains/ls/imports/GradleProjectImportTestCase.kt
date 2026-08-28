// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloadItem
import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.util.SystemProperties
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_DAEMON_NO_IDLE_TIMEOUT
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_JAVA_HOME_PROPERTY
import com.jetbrains.ls.imports.gradle.GradleToolingApiHelper.LSP_GRADLE_PROJECT_INIT_SCRIPTS
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.WorkspaceData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

private const val GRADLE_CLEANUP_ATTEMPTS = 3
private const val GRADLE_CLEANUP_RETRY_DELAY_MS = 300L
private val LOG: Logger = logger<GradleProjectImportTestCase>()

abstract class GradleProjectImportTestCase : AbstractProjectImportTestCase() {

    override fun tearDown() {
        super.tearDown()
        System.clearProperty(LSP_GRADLE_PROJECT_INIT_SCRIPTS)
    }

    protected fun withIgnoredJdkRoots(data: WorkspaceData): WorkspaceData = data.copy(
        sdks = data.sdks.map {
            it.copy(
                roots = emptyList(),
                homePath = null
            )
        }
    )

    protected fun WorkspaceData.withoutSyntheticLibraries(): WorkspaceData {
        val testDataPath = realTestDataDir.toString()
        return copy(
            modules = modules.map { moduleData ->
                moduleData.copy(
                    dependencies = moduleData.dependencies.filter { dependencyData ->
                        !(dependencyData is DependencyData.Library && dependencyData.name.contains(testDataPath))
                    }
                )
            }
        )
    }

    protected fun WorkspaceData.withRelaxedDependencyOrder(): WorkspaceData = copy(
        modules = modules.map { moduleData ->
            moduleData.copy(
                dependencies = moduleData.dependencies.sortedWith { first, second -> first.compare(second) }
            )
        },
        libraries = libraries.sortedBy { it.name }
            .map { library -> library.copy(roots = library.roots.sortedBy { it.rootSortKey() }) }
    )

    protected fun doGradleTest(project: String, resultMapper: (WorkspaceData) -> WorkspaceData = { it }) =
        doGradleTest(project, JdkDownloaderFacade.jdk17, resultMapper) { }

    protected fun doGradleTest(
        project: String,
        jdkToUse: JdkDownloadItem,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it }
    ) = doGradleTest(project, jdkToUse, resultMapper) { }

    protected fun doGradleTest(
        project: String,
        jdkToUse: JdkDownloadItem,
        resultMapper: (WorkspaceData) -> WorkspaceData = { it },
        importParametersCustomizer: (WorkspaceImportParameters) -> WorkspaceImportParameters = { it },
        entityStorageVerifier: (EntityStorage) -> Unit,
    ) {
        downloadGradleBinaries()
        withGradleUserHomeIsolation {
            withConditionalScopedSystemProperty(
                condition = { System.getenv("TEAMCITY_VERSION") != null && !project.contains("android", true) },
                key = LSP_GRADLE_PROJECT_INIT_SCRIPTS,
                value = getCacheRedirectorInitScriptPath().toString()
            ) {
                withScopedSystemProperties(
                    LSP_GRADLE_JAVA_HOME_PROPERTY to jdkToUse.home.toString(),
                    LSP_GRADLE_DAEMON_NO_IDLE_TIMEOUT to "true"
                ) {
                    doTest(
                        project = project,
                        importer = GradleWorkspaceImporter,
                        testDataDir = testDataDir / "gradle",
                        resultMapper = resultMapper,
                        entityStorageVerifier = entityStorageVerifier,
                        importParametersCustomizer = importParametersCustomizer
                    )
                }
            }
        }
    }


    /**
     * Some 'adhoc' libraries were not resolved by coordinates, but as 'jars' directly.
     * The jar path is used as part of the library name, which shall be sanitized for tests
     */
    protected fun WorkspaceData.withSanitizedJarLibraryNames(): WorkspaceData {
        val jarLibraryRegex = Regex("""Gradle: (?<path>.*\.jar)""")

        return copy(
            libraries = libraries.map { library ->
                val match = jarLibraryRegex.matchEntire(library.name) ?: return@map library
                val path = Path(match.groups["path"]!!.value)
                library.copy(name = "Gradle: #####/${path.fileName}")
            }
        )
    }

    // Windows only: run against a fresh, isolated Gradle user home so tests don't share the machine-wide
    // '~/.gradle' kotlin-dsl script compilation cache, whose Windows file-locking races produce the flaky
    // 'Settings_gradle.<init>' NoSuchMethodError. On other OSes keep the shared home to reuse the daemon and
    // its caches. withCustomUserHome copies the wrapper distribution over so isolation doesn't force a re-download.
    private fun withGradleUserHomeIsolation(action: () -> Unit) {
        if (!SystemInfo.isWindows) {
            action()
            return
        }
        withCustomUserHome { gradleUserHomePath ->
            withScopedSystemProperties(
                GradleToolingApiHelper.LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY to gradleUserHomePath,
                action = action
            )
        }
    }

    private fun withCustomUserHome(action: (String) -> Unit) {
        // Unique per test: never reuse (and never hard-delete) a home a lingering Gradle daemon may still
        // lock on Windows, which is what the start-of-test recreateDir() used to fail on. The uniqueness lives
        // in the parent dir; the home leaf is kept named '.gradle' so cache jar paths still contain the
        // literal 'gradle/caches' / 'gradle/wrapper/dists' that cropJarPaths normalizes in workspace.json.
        val gradleHomeParent = createTempDirectory(realTestDataDir, "gradle-user-home-")
        val gradleUserHomePath = (gradleHomeParent / ".gradle").also { it.createDirectories() }
        try {
            // Single-use daemon: the Tooling API spawns a daemon that stops right after the build, releasing
            // its file locks so best-effort cleanup succeeds and daemons do not pile up across tests.
            (gradleUserHomePath / "gradle.properties").writeText("org.gradle.daemon=false\n")
            val systemUserHome = getGradleUserHome() ?: return
            copyGradleDistribution(systemUserHome, gradleUserHomePath)
            action(gradleUserHomePath.toString())
        } finally {
            deleteRecursivelyBestEffort(gradleHomeParent)
        }
    }

    // The Gradle daemon may still hold files in '.gradle' right after import; a cleanup-only failure
    // must not fail an otherwise-passing test. Retry briefly to let handles release, then give up quietly.
    private fun deleteRecursivelyBestEffort(path: Path) {
        repeat(GRADLE_CLEANUP_ATTEMPTS) { attempt ->
            try {
                NioFiles.deleteRecursively(path)
                return
            } catch (e: IOException) {
                if (attempt == GRADLE_CLEANUP_ATTEMPTS - 1) {
                    LOG.warn("Best-effort cleanup of $path failed; leaving it for the test runner to reclaim", e)
                    return
                }
                Thread.sleep(GRADLE_CLEANUP_RETRY_DELAY_MS)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyGradleDistribution(gradleUserHome: Path, newGradleUserHome: Path) {
        assertTrue(newGradleUserHome.exists())
        val source = gradleUserHome.resolve("wrapper/dists")
        if (!source.exists()) {
            return
        }
        val destination = newGradleUserHome.resolve("wrapper/dists")
        assertFalse(destination.exists())
        destination.createDirectories()
        source.copyToRecursively(destination, { _, _, exception -> throw exception }, false, false)
    }

    private fun getGradleUserHome(): Path? {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME") ?: System.getProperty("gradle.user.home")
        if (gradleUserHome != null) {
            return Path.of(gradleUserHome)
        }
        val userHome = SystemProperties.getUserHome()
        return Path.of(userHome).resolve(".gradle")
    }

    private fun getCacheRedirectorInitScriptPath(): Path {
        return createTempFile("lsp-test-cache-redirector-patch", ".gradle").also {
            it.writeText(
                """
                allprojects {
                    repositories {
                        maven {
                            url = 'https://repo.labs.intellij.net/repo1'
                        }
                    }
                }
            """.trimIndent()
            )
        }
    }
}
