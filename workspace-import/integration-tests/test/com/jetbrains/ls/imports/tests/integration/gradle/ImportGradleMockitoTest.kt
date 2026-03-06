// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.gradle

import com.intellij.ide.starter.extended.data.TestCases
import com.intellij.workspaceModel.integrationTests.data.gradle.gradleMockito.gradleMockitoModulesData
import com.jetbrains.ls.imports.tests.integration.gradleTest
import com.jetbrains.ls.imports.tests.integration.withIgnoringGradleDistributiveChecksum
import com.jetbrains.ls.imports.tests.integration.withIgnoringNonClassesRoots
import org.junit.jupiter.api.Test

class ImportGradleMockitoTest {
    @Test
    fun importGradleMockito() {
        gradleTest(TestCases.IU.GradleMockito, gradleMockitoModulesData) {
            withIgnoringGradleDistributiveChecksum(withIgnoringNonClassesRoots(it))
        }
    }
}
