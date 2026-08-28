// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.core.provider.TestDataDirSource
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import org.junit.jupiter.api.Test
import kotlin.io.path.div

@TestDataDirSource
class JpsProjectImportTest : AbstractProjectImportTestCase() {

    @Test
    fun jpsKotlinFacet() = doJpsTest("JpsKotlinFacet")

    @Test
    fun jpsJavaModule() = doJpsTest("JpsJavaModule")

    @Test
    fun jpsExportedModuleLibrary() = doJpsTest("JpsExportedModuleLibrary")

    private fun doJpsTest(project: String) {
        doTest(project, JpsWorkspaceImporter, testDataDir / "jps")
    }
}
