// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.NioFiles.copyRecursively
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div


@EnabledOnOs(OS.MAC, OS.LINUX)
class ProjectImportWithLinksTest : AbstractProjectImportTest() {
    private lateinit var tempDir: Path
    private lateinit var linkedTestDataDir: Path

    override val testDataDir: Path
        get() = linkedTestDataDir

    @BeforeEach
    override fun setUp() {
        super.setUp()
        val originalTestDataDir = PathManager.getHomeDir() /
                "language-server" / "community" / "workspace-import" / "test" / "testData"

        tempDir = createTempDirectory("ProjectImportWithLinks")
        
        // Create a complex nested structure with symlinks and hard links
        // Structure:
        // tempDir/
        //   real_data/ (actual copy of test data)
        //   link1 -> real_data
        //   nested/
        //     link2 -> ../link1
        //     final_data/ (path we will use) -> link2
        
        val realData = tempDir / "real_data"
        realData.createDirectory()
        copyRecursively(originalTestDataDir, realData)

        val link1 = tempDir / "link1"
        Files.createSymbolicLink(link1, realData)

        val nested = tempDir / "nested"
        nested.createDirectory()

        val link2 = nested / "link2"
        Files.createSymbolicLink(link2, Path.of("../link1"))

        linkedTestDataDir = nested / "final_data"
        Files.createSymbolicLink(linkedTestDataDir, Path.of("link2"))
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        tempDir.toFile().deleteRecursively()
    }

    override fun getRealTestDataDir(): String {
        return tempDir.toString()
    }
}
