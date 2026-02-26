// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.maven

import com.intellij.ide.starter.extended.allure.Subsystems
import com.intellij.ide.starter.extended.data.TestCases
import com.jetbrains.ls.imports.tests.integration.LspTestData
import com.jetbrains.ls.imports.tests.integration.mavenTest
import org.junit.jupiter.api.Test

@Subsystems.LspWorkspaceImport
class ImportMavenEmptyProjectCustomSrcRootTest {
    @Test
    fun importMavenEmptyProjectCustomSrcRoot() {
        mavenTest(TestCases.IU.MavenEmptyProjectCustomSrcRoot, LspTestData.MavenEmptyProjectCustomSrcRootModulesData)
    }
}
