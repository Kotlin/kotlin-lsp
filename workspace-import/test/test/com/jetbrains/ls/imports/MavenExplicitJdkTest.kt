// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.maven.MavenTool.Companion.explicitMavenJdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.div

/** Which Maven `JAVA_HOME` becomes the project SDK. */
class MavenExplicitJdkTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun nullHome() {
        assertNull(explicitMavenJdk(null, serverJavaHome = null))
    }

    @Test
    fun blankHome() {
        assertNull(explicitMavenJdk("  ", serverJavaHome = null))
    }

    @Test
    fun missingPath() {
        assertNull(explicitMavenJdk((tempDir / "missing").toString(), serverJavaHome = null))
    }

    @Test
    fun regularFile() {
        val file = (tempDir / "file").createFile()
        assertNull(explicitMavenJdk(file.toString(), serverJavaHome = null))
    }

    @Test
    fun serverJvm() {
        val server = (tempDir / "server").createDirectory()
        assertNull(explicitMavenJdk(server.toString(), serverJavaHome = server.toString()))
    }

    @Test
    fun serverJvmThroughSymlink() {
        val server = (tempDir / "server").createDirectory()
        val link = tempDir / "link"
        try {
            Files.createSymbolicLink(link, server)
        }
        catch (e: IOException) {
            assumeTrue(false, "Cannot create a symlink: $e")
        }
        catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Cannot create a symlink: $e")
        }
        assertNull(explicitMavenJdk(link.toString(), serverJavaHome = server.toString()))
        assertNull(explicitMavenJdk(server.toString(), serverJavaHome = link.toString()))
    }

    @Test
    fun validDirectory() {
        val jdk = (tempDir / "jdk").createDirectory()
        val server = (tempDir / "server").createDirectory()
        assertEquals(jdk, explicitMavenJdk(jdk.toString(), serverJavaHome = server.toString()))
        assertEquals(jdk, explicitMavenJdk(jdk.toString(), serverJavaHome = null))
    }
}
