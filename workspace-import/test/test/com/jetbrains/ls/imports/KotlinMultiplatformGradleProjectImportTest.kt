// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ide.starter.sdk.JdkDownloaderFacade
import com.intellij.util.system.CpuArch
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.core.provider.TestDataDirSource
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
        )

        reporter.capturedOutput
            .run {
                assertContainsOnce("Gradle execution complete")

                assertContainsOnce("Task :prepareKotlinIdeaImport")
                assertContainsOnce("Task :app:desktopApp:prepareKotlinIdeaImport")
                assertContainsOnce("Task :app:shared:prepareKotlinIdeaImport")
            }
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
}
