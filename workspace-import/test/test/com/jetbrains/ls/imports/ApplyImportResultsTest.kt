// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryPropertiesEntity
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.libraryProperties
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.workspaceModel.ide.impl.createIdeVirtualFileUrlManager
import com.jetbrains.ls.imports.api.DependencySubstitution
import com.jetbrains.ls.imports.api.LSConfiguredProjectData
import com.jetbrains.ls.imports.api.LSFolderImportRecord
import com.jetbrains.ls.imports.api.LSFolderImportStatus
import com.jetbrains.ls.imports.api.LSImportedFoldersDataEntity
import com.jetbrains.ls.imports.api.ModuleCoordinateEntity
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.applyImportResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplyImportResultsTest {
    private val urlManager = createIdeVirtualFileUrlManager(true)

    private val folderA: VirtualFileUrl get() = url("/workspace/a")
    private val folderB: VirtualFileUrl get() = url("/workspace/b")
    private val removedFolder: VirtualFileUrl get() = url("/workspace/removed")

    @Test
    fun `failed target keeps its previously imported entities while the succeeded one is refreshed`() {
        // Previous import: all three folders imported fine.
        val storage = importInto(
            MutableEntityStorage.create(),
            folderA to importedModel(folderA, "a"),
            folderB to importedModel(folderB, "b"),
            removedFolder to importedModel(removedFolder, "removed"),
        ).apply { addBookkeepingEntity(folderA) }

        // Reload: `a` re-imports with a renamed module, `b` fails, `removed` is not imported anymore.
        importInto(storage, folderA to importedModel(folderA, "a-renamed"), folderB to null)

        assertEquals(setOf("a-renamed", "b"), storage.moduleNames())
        // The failed target keeps its content root, so nothing has to be reindexed for it.
        assertEquals(setOf(folderA.url, folderB.url), storage.entities<ContentRootEntity>().map { it.url.url }.toSet())
        // The stale bookkeeping entity is gone even though its source belongs to a kept folder: exactly one is
        // expected in the model, and the caller adds the up-to-date one.
        assertEquals(0, storage.entities<LSImportedFoldersDataEntity>().count())
    }

    @Test
    fun `all targets failing keeps the whole previously imported model`() {
        val storage = importInto(
            MutableEntityStorage.create(),
            folderA to importedModel(folderA, "a"),
            folderB to importedModel(folderB, "b"),
        )

        importInto(storage, folderA to null, folderB to null)

        assertEquals(setOf("a", "b"), storage.moduleNames())
        assertEquals(setOf(folderA.url, folderB.url), storage.entities<ContentRootEntity>().map { it.url.url }.toSet())
    }

    @Test
    fun `dependency of a kept module on a module that is gone is dropped`() {
        val storage = importInto(
            MutableEntityStorage.create(),
            folderA to importedModel(folderA, "a"),
            // `b` depends on `a` of the other folder, without any library behind it to fall back to.
            folderB to importedModel(folderB, "b", ModuleDependency(ModuleId("a"), false, DependencyScope.COMPILE, false)),
        )

        // `a` re-imports under a different module name, `b` fails and keeps its now-dangling dependency on `a`.
        importInto(storage, folderA to importedModel(folderA, "a-renamed"), folderB to null)

        assertEquals(setOf("a-renamed", "b"), storage.moduleNames())
        assertEquals(emptyList<ModuleDependencyItem>(), storage.module("b").dependencies)
    }

    /**
     * The Gradle module depends on library `com.example:b:1.0`, which the Maven project produces as a module, so the
     * dependency is substituted onto that module. Then Gradle fails to reload and Maven no longer produces the
     * coordinate: the kept Gradle module must go back to depending on the library.
     */
    @Test
    fun `substituted dependency of a kept module falls back to its library when the producing module is gone`() {
        val gradle = url("/workspace/gradle")
        val maven = url("/workspace/maven")
        val storage = importInto(
            MutableEntityStorage.create(),
            gradle to gradleModelWithLibraryDependency(gradle, "com.example:b:1.0"),
            maven to mavenModelProducing(maven, "com.example:b:1.0"),
        )
        assertEquals(
            ModuleDependency(ModuleId("maven-producer"), false, DependencyScope.COMPILE, false),
            storage.module("gradle-consumer").dependencies.single(),
            "the dependency is expected to be substituted onto the module producing the coordinate",
        )

        // Maven re-imports without the coordinate-producing module; Gradle fails and keeps its entities.
        importInto(storage, gradle to null, maven to importedModel(maven, "maven-other"))

        assertEquals(setOf("gradle-consumer", "maven-other"), storage.moduleNames())
        val dependency = storage.module("gradle-consumer").dependencies.single()
        assertEquals(LibraryDependency(storage.libraryId("b"), false, DependencyScope.COMPILE), dependency)
    }

    /** Runs one import over [storage], carrying the dependency substitutions across calls like the server does. */
    private fun importInto(
        storage: MutableEntityStorage,
        vararg results: Pair<VirtualFileUrl, EntityStorage?>,
    ): MutableEntityStorage {
        storage.applyImportResults(results.toList(), substitutions)
        return storage
    }

    private val substitutions = mutableListOf<DependencySubstitution>()

    private fun url(path: String): VirtualFileUrl = urlManager.getOrCreateFromUrl("file://$path")

    private fun EntityStorage.moduleNames(): Set<String> = entities<ModuleEntity>().map { it.name }.toSet()

    private fun EntityStorage.module(name: String): ModuleEntity = entities<ModuleEntity>().single { it.name == name }

    private fun EntityStorage.libraryId(name: String) = entities<LibraryEntity>().single { it.name == name }.symbolicId

    /**
     * A minimal importer result: one module with a content root, sourced at the imported [folder], as importers do.
     * Kept as a builder on purpose — the merge applies its change log, exactly like a real importer diff.
     */
    private fun importedModel(
        folder: VirtualFileUrl,
        moduleName: String,
        vararg dependencies: ModuleDependencyItem,
    ): EntityStorage {
        val source = WorkspaceEntitySource(folder)
        return MutableEntityStorage.create().apply {
            addEntity(ModuleEntity(moduleName, dependencies.toList(), source) {
                contentRoots = listOf(ContentRootEntity(folder, emptyList(), source))
            })
        }
    }

    /** A module depending on a library that carries [coordinate], as the Gradle importer produces it. */
    private fun gradleModelWithLibraryDependency(folder: VirtualFileUrl, coordinate: String): EntityStorage {
        val source = WorkspaceEntitySource(folder)
        val (groupId, artifactId, version) = coordinate.split(':')
        return MutableEntityStorage.create().apply {
            val library = this addEntity LibraryEntity(artifactId, LibraryTableId.ProjectLibraryTableId, emptyList(), source) {
                libraryProperties = LibraryPropertiesEntity(source) {
                    propertiesXmlTag = """<properties groupId="$groupId" artifactId="$artifactId" version="$version" />"""
                }
            }
            addEntity(ModuleEntity("gradle-consumer", listOf(LibraryDependency(library.symbolicId, false, DependencyScope.COMPILE)), source) {
                contentRoots = listOf(ContentRootEntity(folder, emptyList(), source))
            })
        }
    }

    /** A module publishing [coordinate], as the Maven importer produces it. */
    private fun mavenModelProducing(folder: VirtualFileUrl, coordinate: String): EntityStorage {
        val source = WorkspaceEntitySource(folder)
        return MutableEntityStorage.create().apply {
            val module = ModuleEntity("maven-producer", emptyList(), source) {
                contentRoots = listOf(ContentRootEntity(folder, emptyList(), source))
            }
            addEntity(module)
            addEntity(ModuleCoordinateEntity(coordinate, source) { this.module = module })
        }
    }

    private fun MutableEntityStorage.addBookkeepingEntity(folder: VirtualFileUrl) {
        addEntity(
            LSImportedFoldersDataEntity(
                folderImports = listOf(LSFolderImportRecord(folder.url, "test", LSFolderImportStatus.SUCCESS)),
                configuredProjects = emptyList<LSConfiguredProjectData>(),
                entitySource = WorkspaceEntitySource(folder),
            )
        )
    }
}
