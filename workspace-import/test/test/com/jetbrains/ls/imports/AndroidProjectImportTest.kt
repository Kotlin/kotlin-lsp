// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@TestDataDirSource
@EnabledIfEnvironmentVariable(named = "ANDROID_HOME", matches = ".*", disabledReason = "Android SDK is required")
class AndroidProjectImportTest : GradleProjectImportTestCase() {

    @Test
    fun androidMultiModule() = doGradleTest("AndroidMultiModule") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames().withoutSyntheticLibraries()
    }

    @Test
    fun androidDependingOnKotlinJvm() = doGradleTest("AndroidDependingOnKotlinJvm") { workspaceData ->
        withIgnoredJdkRoots(workspaceData).withSanitizedJarLibraryNames().withoutSyntheticLibraries()
    }
}
