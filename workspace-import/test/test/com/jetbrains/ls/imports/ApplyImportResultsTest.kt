// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.ls.server.importing.applyImportResults
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
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.jps.entities.libraryProperties
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.ExternalMappingKey
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import com.intellij.platform.workspace.storage.instrumentation.MutableEntityStorageInstrumentation
import com.intellij.platform.workspace.storage.toBuilder
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.ls.imports.api.LSConfiguredProjectData
import com.jetbrains.ls.imports.api.LSDependencySubstitutionEntity
import com.jetbrains.ls.imports.api.LSFolderImportRecord
import com.jetbrains.ls.imports.api.LSFolderImportStatus
import com.jetbrains.ls.imports.api.LSImportedFoldersDataEntity
import com.jetbrains.ls.imports.api.ModuleCoordinateEntity
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplyImportResultsTest {
    private val urlManager = IdeVirtualFileUrlManagerImpl()

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

    /**
     * A phased importer publishes a snapshot (the JPS one does, because it keeps its builder for the next phase),
     * and the same snapshot is re-applied at every later commit of the cycle. Both must land in the model: a merge
     * that replays a change log would add nothing from a snapshot, and one that consumes the model would add it
     * only once.
     */
    @Test
    fun `a snapshot model is imported, and re-importing it changes nothing`() {
        val model = (importedModel(folderA, "a") as MutableEntityStorage).toSnapshot()

        val storage = importInto(MutableEntityStorage.create(), folderA to model)
        assertEquals(setOf("a"), storage.moduleNames())

        importInto(storage, folderA to model)
        assertEquals(setOf("a"), storage.moduleNames())
        assertEquals(setOf(folderA.url), storage.entities<ContentRootEntity>().map { it.url.url }.toSet())
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
        assertEquals(0, storage.entities<LSDependencySubstitutionEntity>().count(), "the stale record is expected to be dropped")
    }

    /**
     * A phased import cycle commits every result as it arrives, so the consumer of a coordinate can reach the model
     * before its producer does. The commit that has only the consumer cannot substitute anything, and the commit that
     * adds the producer re-applies the consumer's model in full, so the substitution must appear then.
     */
    @Test
    fun `substitution appears once a later commit adds the producing target`() {
        val gradle = url("/workspace/gradle")
        val maven = url("/workspace/maven")
        val consumer = gradleModelWithLibraryDependency(gradle, "com.example:b:1.0")

        // First commit of the cycle: the Gradle target is done, the Maven one is still importing (no model yet).
        val storage = importInto(MutableEntityStorage.create(), gradle to consumer, maven to null)
        assertEquals(
            LibraryDependency(storage.libraryId("b"), false, DependencyScope.COMPILE),
            storage.module("gradle-consumer").dependencies.single(),
            "nothing produces the coordinate yet, so the library dependency is expected to stay",
        )

        // Second commit: the Maven result arrives, both models are re-applied.
        importInto(storage, gradle to consumer, maven to mavenModelProducing(maven, "com.example:b:1.0"))

        assertEquals(
            ModuleDependency(ModuleId("maven-producer"), false, DependencyScope.COMPILE, false),
            storage.module("gradle-consumer").dependencies.single(),
            "the substitution is expected to be recomputed over the completed model",
        )
        assertEquals(1, storage.entities<LSDependencySubstitutionEntity>().count())
    }

    /** The counterpart of the test above: a target that re-imports fine keeps its substitution, recomputed from scratch. */
    @Test
    fun `successful reimport recomputes the substitution instead of falling back to the library`() {
        val gradle = url("/workspace/gradle")
        val maven = url("/workspace/maven")
        val storage = importInto(
            MutableEntityStorage.create(),
            gradle to gradleModelWithLibraryDependency(gradle, "com.example:b:1.0"),
            maven to mavenModelProducing(maven, "com.example:b:1.0"),
        )

        // Both targets re-import fine and the coordinate is still produced: the substitution must stay.
        importInto(
            storage,
            gradle to gradleModelWithLibraryDependency(gradle, "com.example:b:1.0"),
            maven to mavenModelProducing(maven, "com.example:b:1.0"),
        )

        assertEquals(
            ModuleDependency(ModuleId("maven-producer"), false, DependencyScope.COMPILE, false),
            storage.module("gradle-consumer").dependencies.single(),
        )
        assertEquals(1, storage.entities<LSDependencySubstitutionEntity>().count(), "exactly one up-to-date record is expected")
    }

    /**
     * A targeted cycle re-imports only `a` and seeds `b` with the model its last commit applied (`runImportCycle`).
     * Seeding re-applies every entity of that model, also one sourced outside [WorkspaceEntitySource] (a default JDK
     * added for a module that inherits the project SDK); marking `b` kept would hold only its sourced entities.
     */
    @Test
    fun `a target seeded with its last model keeps an entity sourced outside the workspace, which keeping would drop`() {
        val modelB = modelWithDefaultSdk(folderB, "b")
        val seeded = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a"), folderB to modelB)
        val kept = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a"), folderB to modelB)

        importInto(seeded, folderA to importedModel(folderA, "a"), folderB to modelB)
        importInto(kept, folderA to importedModel(folderA, "a"), folderB to null)

        assertEquals(listOf(DEFAULT_SDK), seeded.entities<SdkEntity>().map { it.name }.toList())
        assertEquals(SdkDependency(SdkId(DEFAULT_SDK, "JavaSDK")), seeded.module("b").dependencies.single())
        assertEquals(emptyList<String>(), kept.entities<SdkEntity>().map { it.name }.toList(), "a kept target loses the default SDK")
    }

    /**
     * The commit skip asks [MutableEntityStorageInstrumentation.hasSameEntities] whether a merge of equal models over the
     * committed one changed anything. The merge removes every entity and adds it again, so each round moves the entities
     * to the other set of free slots; the answer must not depend on that layout, hence three rounds in a row.
     */
    @Test
    fun `re-applying the same models has the same entities in every round`() {
        val gradle = url("/workspace/gradle")
        val maven = url("/workspace/maven")
        val snapshotModel = (importedModel(folderA, "a", ModuleDependency(ModuleId("maven-producer"), false, DependencyScope.COMPILE, false)) as MutableEntityStorage).toSnapshot()
        fun results() = arrayOf(
            folderA to snapshotModel,
            gradle to gradleModelWithLibraryDependency(gradle, "com.example:b:1.0"),
            maven to mavenModelProducing(maven, "com.example:b:1.0"),
        )
        var base = importInto(MutableEntityStorage.create(), *results()).apply { addBookkeepingEntity(folderA) }.toSnapshot()
        repeat(3) { round ->
            val merged = importInto(base.toBuilder(), *results()).apply { addBookkeepingEntity(folderA) }
            assertTrue(merged.hasSameEntities(), "round $round: equal models are expected to have the same entities")
            assertEquals(1, merged.entities<LSDependencySubstitutionEntity>().count(), "round $round: the substitution is expected to be there")
            base = merged.toSnapshot()
        }
    }

    @Test
    fun `a renamed module has other entities`() {
        val base = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a")).toSnapshot()
        val merged = importInto(base.toBuilder(), folderA to importedModel(folderA, "a-renamed"))
        assertFalse(merged.hasSameEntities())
    }

    @Test
    fun `a changed dependency has other entities`() {
        val base = importInto(
            MutableEntityStorage.create(),
            folderA to importedModel(folderA, "a"),
            folderB to importedModel(folderB, "b"),
        ).toSnapshot()
        val merged = importInto(
            base.toBuilder(),
            folderA to importedModel(folderA, "a"),
            folderB to importedModel(folderB, "b", ModuleDependency(ModuleId("a"), false, DependencyScope.COMPILE, false)),
        )
        assertFalse(merged.hasSameEntities())
    }

    @Test
    fun `a changed content root url has other entities`() {
        val base = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a")).toSnapshot()
        val merged = importInto(base.toBuilder(), folderA to importedModel(folderA, "a", contentRoot = url("/workspace/a/src")))
        assertFalse(merged.hasSameEntities())
    }

    /** The bookkeeping entity is part of the compared storage, so a status change alone still commits. */
    @Test
    fun `a changed import status in the bookkeeping entity has other entities`() {
        val base = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a"))
            .apply { addBookkeepingEntity(folderA, LSFolderImportStatus.FAILED) }
            .toSnapshot()
        val merged = importInto(base.toBuilder(), folderA to importedModel(folderA, "a"))
            .apply { addBookkeepingEntity(folderA, LSFolderImportStatus.SUCCESS) }
        assertFalse(merged.hasSameEntities())
    }

    @Test
    fun `a failed target next to unchanged ones has the same entities`() {
        val base = importInto(
            MutableEntityStorage.create(),
            folderA to importedModel(folderA, "a"),
            folderB to importedModel(folderB, "b"),
        ).apply { addBookkeepingEntity(folderA) }.toSnapshot()
        val merged = importInto(base.toBuilder(), folderA to importedModel(folderA, "a"), folderB to null)
            .apply { addBookkeepingEntity(folderA) }
        assertTrue(merged.hasSameEntities())
    }

    /**
     * The committed model carries the bridges of its modules and SDKs as external mappings, and the check gives up on
     * an entity that has one. That is why the skip compares against the storage the import built, before the bridges.
     */
    @Test
    fun `a base whose module has an external mapping never has the same entities`() {
        val base = importInto(MutableEntityStorage.create(), folderA to importedModel(folderA, "a")).apply {
            getMutableExternalMapping(ExternalMappingKey.create<Any>("test.bridge")).addMapping(entities<ModuleEntity>().single(), Any())
        }.toSnapshot()
        val merged = importInto(base.toBuilder(), folderA to importedModel(folderA, "a"))
        assertFalse(merged.hasSameEntities())
    }

    /** Runs one import over [storage]; the dependency substitutions are carried across calls by the model itself. */
    private fun importInto(
        storage: MutableEntityStorage,
        vararg results: Pair<VirtualFileUrl, EntityStorage?>,
    ): MutableEntityStorage {
        storage.applyImportResults(results.toList())
        return storage
    }

    private fun url(path: String): VirtualFileUrl = urlManager.storeAndGet("file://$path")

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
        contentRoot: VirtualFileUrl = folder,
    ): EntityStorage {
        val source = WorkspaceEntitySource(folder)
        return MutableEntityStorage.create().apply {
            addEntity(ModuleEntity(moduleName, dependencies.toList(), source) {
                contentRoots = listOf(ContentRootEntity(contentRoot, emptyList(), source))
            })
        }
    }

    @OptIn(EntityStorageInstrumentationApi::class)
    private fun MutableEntityStorage.hasSameEntities(): Boolean = (this as MutableEntityStorageInstrumentation).hasSameEntities()

    /** A module on a default JDK that is sourced outside the workspace, as `fixMissingProjectSdk` adds it. */
    private fun modelWithDefaultSdk(folder: VirtualFileUrl, moduleName: String): EntityStorage {
        val source = WorkspaceEntitySource(folder)
        return MutableEntityStorage.create().apply {
            addEntity(SdkEntity(name = DEFAULT_SDK, type = "JavaSDK", roots = emptyList(), additionalData = "", entitySource = DefaultSdkSource))
            addEntity(ModuleEntity(moduleName, listOf(SdkDependency(SdkId(DEFAULT_SDK, "JavaSDK"))), source) {
                contentRoots = listOf(ContentRootEntity(folder, emptyList(), source))
            })
        }
    }

    private object DefaultSdkSource : EntitySource

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

    private fun MutableEntityStorage.addBookkeepingEntity(folder: VirtualFileUrl, status: LSFolderImportStatus = LSFolderImportStatus.SUCCESS) {
        addEntity(
            LSImportedFoldersDataEntity(
                folderImports = listOf(LSFolderImportRecord(folder.url, "test", status)),
                configuredProjects = emptyList<LSConfiguredProjectData>(),
                blockedFolders = emptyList(),
                entitySource = WorkspaceEntitySource(folder),
            )
        )
    }
}

private const val DEFAULT_SDK = "Java SDK"
