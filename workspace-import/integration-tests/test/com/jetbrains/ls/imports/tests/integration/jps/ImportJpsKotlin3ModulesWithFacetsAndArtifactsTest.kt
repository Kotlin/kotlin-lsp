// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.jps

import com.intellij.ide.starter.extended.data.TestCases
import com.intellij.workspaceModel.integrationTests.data.jps.jpsKotlin3ModulesWithFacetsAndArtifactsModules
import com.jetbrains.ls.imports.tests.integration.jpsTest
import org.junit.jupiter.api.Test

class ImportJpsKotlin3ModulesWithFacetsAndArtifactsTest {
    @Test
    fun importJpsKotlin3ModulesWithFacetsAndArtifacts() {
        jpsTest(TestCases.IU.JpsKotlin3ModulesWithFacetsAndArtifacts, jpsKotlin3ModulesWithFacetsAndArtifactsModules)
    }
}