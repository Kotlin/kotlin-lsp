// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.maven

import com.intellij.ide.starter.extended.data.TestCases
import com.intellij.workspaceModel.integrationTests.data.maven.mavenWithEjbFacet.mavenWithEjbFacetModulesData
import com.jetbrains.ls.imports.tests.integration.mavenTest
import org.junit.jupiter.api.Test

class ImportMavenWithEjbFacetTest {
    @Test
    fun importMavenWithEjbFacet() {
        mavenTest(TestCases.IU.MavenWithEjbFacet, mavenWithEjbFacetModulesData)
    }
}
