// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.openapi.application.PathManager
import java.nio.file.Path
import kotlin.io.path.div

class ProjectImportTest : AbstractProjectImportTest() {
    override val testDataDir: Path = PathManager.getHomeDir() /
            "language-server" / "community" / "workspace-import" / "test" / "testData"
}