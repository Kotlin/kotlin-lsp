// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.util

import com.jetbrains.ls.imports.gradle.getLibraryName
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency
import com.jetbrains.ls.imports.gradle.putNotNullValue
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.XmlElement
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File

internal class ProjectLibraryIndex {

    private val index: MutableMap<String, LibraryData> = mutableMapOf()

    fun add(dependency: LibraryData) {
        index.compute(dependency.name) { _, oldLibrary ->
            return@compute oldLibrary?.withAdditionalRoots(dependency.roots) ?: dependency
        }
    }

    fun add(moduleName: String, dependency: IdeaSingleEntryLibraryDependency) {
        index.compute(dependency.getLibraryName()) { _, oldLibrary ->
            return@compute oldLibrary?.withAdditionalRoots(dependency.getRoots()) ?: dependency.toLibraryData(moduleName)
        }
    }

    fun add(moduleName: String, dependency: ExternalModuleDependency) {
        index.compute(dependency.mavenCoordinates) { _, libraryData ->
            return@compute libraryData?.withRoot(dependency.file) ?: dependency.toLibraryData(moduleName)
        }
    }

    fun getLibraries(): List<LibraryData> = index.values.toList()

    private fun ExternalModuleDependency.toLibraryData(moduleName: String): LibraryData {
        return LibraryData(
            name = "Gradle: ${mavenCoordinates}",
            module = moduleName,
            type = "COMPILE",
            roots = this.let {
                val result = mutableListOf<LibraryRootData>()
                it.file.run { if (exists()) result.add(LibraryRootData(path, "CLASSES")) }
                result
            }
        )
    }

    private fun LibraryData.withRoot(root: File) = withAdditionalRoots(
        listOf(
            LibraryRootData(
                path = root.path
            )
        )
    )

    private fun LibraryData.withAdditionalRoots(additionalRoots: List<LibraryRootData>): LibraryData {
        val newRoots: MutableList<LibraryRootData> = mutableListOf()
        newRoots.addAll(roots)
        for (root in additionalRoots) {
            if (!newRoots.contains(root)) {
                newRoots.add(root)
            }
        }
        if (newRoots != roots) {
            return copy(roots = newRoots.sortedBy { it.path })
        } else {
            return this
        }
    }

    private fun IdeaSingleEntryLibraryDependency.getRoots(): List<LibraryRootData> {
        val result = mutableListOf<LibraryRootData>()
        file?.run { if (exists()) result.add(LibraryRootData(path, "CLASSES")) }
        source?.run { if (exists()) result.add(LibraryRootData(path, "SOURCES")) }
        javadoc?.run { if (exists()) result.add(LibraryRootData(path, "JAVADOC")) }
        return result
    }

    private fun IdeaSingleEntryLibraryDependency.toLibraryData(moduleName: String): LibraryData {
        val libraryName = getLibraryName()
        return LibraryData(
            name = libraryName,
            module = moduleName,
            type = scope.scope,
            roots = getRoots(),
            properties = getProperties()
        )
    }

    private fun IdeaSingleEntryLibraryDependency.getProperties(): XmlElement? {
        if (gradleModuleVersion == null) {
            return null
        }
        val metadata = mutableMapOf<String, String>()
        metadata.putNotNullValue("groupId", gradleModuleVersion.group)
        metadata.putNotNullValue("artifactId", gradleModuleVersion.name)
        metadata.putNotNullValue("version", gradleModuleVersion.version)
        metadata.putNotNullValue("baseVersion", gradleModuleVersion.version)
        if (metadata.isEmpty()) {
            return null
        }
        return XmlElement(
            tag = "properties",
            attributes = metadata
        )
    }
}
