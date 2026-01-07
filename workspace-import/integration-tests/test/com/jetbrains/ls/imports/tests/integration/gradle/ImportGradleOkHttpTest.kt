// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.gradle

import com.intellij.ide.starter.extended.data.TestCases
import com.intellij.workspaceModel.integrationTests.data.gradle.gradleOkHttp.gradleOkHttpModulesData
import com.jetbrains.ls.imports.tests.integration.gradleTest
import org.junit.jupiter.api.Test

class ImportGradleOkHttpTest {
    @Test
    fun importGradleOkHttp() {
        gradleTest(TestCases.IU.GradleOkHttp, gradleOkHttpModulesData)
    }
}
