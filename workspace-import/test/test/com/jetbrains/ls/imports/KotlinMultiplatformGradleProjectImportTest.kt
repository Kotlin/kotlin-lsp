// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.util.system.CpuArch
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@TestDataDirSource
@EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
class KotlinMultiplatformGradleProjectImportTest : GradleProjectImportTestCase() {
    @Test
    fun kotlinKmpProject() {
        val reporter = LoggingWorkspaceProgressReporter()
        doGradleTest(
            project = "GradleKotlinKmpProject",
            jdkToUse = JdkDownloaderFacade.jdk17,
            reporter = reporter,
            comparator = WorkspaceComparator()
                .withIgnoredJdkRoots()
                .withSanitizedJarLibraryNames()
                .withoutSyntheticLibraries(),
            goldenFileName = goldenFileNameForCurrentPlatform,
            entityStorageVerifier = ::assertJvmAndAndroidOnlyModule,
        )

        reporter.capturedOutput
            .run {
                assertContainsOnce("Gradle execution complete")

                assertContainsOnce("Task :prepareKotlinIdeaImport")
                assertContainsOnce("Task :app:desktopApp:prepareKotlinIdeaImport")
                assertContainsOnce("Task :app:shared:prepareKotlinIdeaImport")
            }
    }

    /**
     * Checks `jvmAndAndroid`, which has only JVM and Android targets:
     * - `commonMain` and `commonTest` have one JVM platform and JVM arguments.
     * - `androidMain` depends on the Android source sets of `core`.
     * - The `commonMain` directory that a task writes is a source root.
     */
    private fun assertJvmAndAndroidOnlyModule(storage: EntityStorage) {
        val kotlinSettings = storage.entities<KotlinSettingsEntity>().associateBy { it.module.name }
        for (sourceSet in listOf("commonMain", "commonTest")) {
            val module = "$JVM_AND_ANDROID.$sourceSet"
            val settings = kotlinSettings.getValue(module)
            val platform = settings.targetPlatform.orEmpty()
            assertTrue(platform.startsWith("JVM [") && "/" !in platform, "$module: the platform $platform is not one JVM platform")
            val arguments = settings.compilerArguments.orEmpty()
            assertTrue(arguments.startsWith("J{") && "\"multiPlatform\":true" in arguments, "$module: $arguments")
        }

        val modules = storage.entities<ModuleEntity>().associateBy { it.name }
        fun moduleDependencies(module: String) =
            modules.getValue(module).dependencies.filterIsInstance<ModuleDependency>().map { it.module.name }
        for (consumer in listOf("$JVM_AND_ANDROID.androidMain", "GradleKotlinKmpProject.app.shared.androidMain")) {
            val dependencies = moduleDependencies(consumer)
            for (expected in listOf("GradleKotlinKmpProject.core.androidMain", "GradleKotlinKmpProject.core.commonMain")) {
                assertTrue(expected in dependencies, "$consumer does not depend on $expected: $dependencies")
            }
        }

        val sourceRoots = modules.getValue("$JVM_AND_ANDROID.commonMain").contentRoots.flatMap { it.sourceRoots }.map { it.url.url }
        assertTrue(sourceRoots.any { it.endsWith("/jvmAndAndroid/build/generated/buildInfo/kotlin") }, "No generated source root: $sourceRoots")
    }

    // The import resolves host-specific artifacts: e.g. the Kotlin/Native distribution and the Compose Desktop Skia runtime.
    // Hacking workspace comparator to be aware of such differences is more trouble than just having separate golden files for each testing
    // environment
    @OptIn(LowLevelLocalMachineAccess::class)
    private val goldenFileNameForCurrentPlatform: String
        get() = "workspace-${OS.CURRENT.name.lowercase()}-${CpuArch.CURRENT.name.lowercase()}.json"

    private fun String.assertContainsOnce(value: String) {
        val firstIndex = indexOf(value)
        assertTrue(firstIndex > 0, "The line '$value' is not found in string [$this]")
        val secondIndex = indexOf(value, firstIndex + value.length, false)
        assertTrue(
            secondIndex == -1,
            "The line '$value' expected to be found in a string only once, but the value was found at $firstIndex and $secondIndex"
        )
    }

    private companion object {
        const val JVM_AND_ANDROID = "GradleKotlinKmpProject.jvmAndAndroid"
    }
}
