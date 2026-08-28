// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.core.provider

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.NioFiles.copyRecursively
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.support.ParameterDeclarations
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedClass
@ArgumentsSource(TestDataDirArgumentsProvider::class)
annotation class TestDataDirSource

class TestDataDirArgumentsProvider : ArgumentsProvider {
    override fun provideArguments(parameters: ParameterDeclarations, context: ExtensionContext): Stream<out Arguments> = buildStream {
        add(DefaultTestDataDirProvider.asArgument())
        if (OS.MAC.isCurrentOs || OS.LINUX.isCurrentOs) {
            add(SymbolicLinkTestDataDirProvider.asArgument())
        }
    }
}

interface TestDataDirProvider {
    fun get(): TestDataDirs
}

interface TestDataDirs : AutoCloseable {
    val testDataDir: Path
    val realTestDataDir: Path
}

internal object DefaultTestDataDirProvider : TestDataDirProvider {

    override fun get(): TestDataDirs = DefaultTestDataDirs

    override fun toString(): String = "default"

    private object DefaultTestDataDirs : TestDataDirs {
        override val testDataDir: Path
            get() = PathManager.getHomeDir() / "language-server" / "community" / "workspace-import" / "test" / "testData"

        override val realTestDataDir: Path
            get() = testDataDir

        override fun close() = Unit
    }
}

// Creates a complex nested structure with symlinks and hard links
// Structure:
// tempDir/
//   real_data/ (actual copy of test data)
//   link1 -> real_data
//   nested/
//     link2 -> ../link1
//     final_data/ (path we will use) -> link2
internal object SymbolicLinkTestDataDirProvider : TestDataDirProvider {

    override fun get(): TestDataDirs {
        val originalTestDataDir = PathManager.getHomeDir() / "language-server" / "community" / "workspace-import" / "test" / "testData"

        val tempDir = createTempDirectory("ProjectImportWithLinks")

        val realData = tempDir / "real_data"
        realData.createDirectory()
        copyRecursively(originalTestDataDir, realData)

        val link1 = tempDir / "link1"
        Files.createSymbolicLink(link1, realData)

        val nested = tempDir / "nested"
        nested.createDirectory()

        val link2 = nested / "link2"
        Files.createSymbolicLink(link2, Path.of("../link1"))

        val linkedTestDataDir = nested / "final_data"
        Files.createSymbolicLink(linkedTestDataDir, Path.of("link2"))

        return SymbolicLinkTestDataDirs(tempDir, linkedTestDataDir)
    }

    override fun toString(): String = "symbolicLink"

    private class SymbolicLinkTestDataDirs(
        private val tempDir: Path,
        override val testDataDir: Path,
    ) : TestDataDirs {

        override val realTestDataDir: Path
            get() = tempDir

        override fun close() {
            @Suppress("IO_FILE_USAGE")
            tempDir.toFile().deleteRecursively()
        }
    }
}

@Suppress("NoStreamApiInKotlin")
private fun <T> buildStream(builderAction: MutableList<T>.() -> Unit): Stream<T> {
    val list = mutableListOf<T>()
    builderAction(list)
    return list.stream()
}

private fun TestDataDirProvider.asArgument() = Arguments.of(this)
