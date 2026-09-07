// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.bufferPhases
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Buffering of the import phases, which is all a `DelegatedBufferizedImporter` adds to the importer it wraps. */
class DelegatedBufferizedImporterTest {
    private val firstPhase: EntityStorage = MutableEntityStorage.create().toSnapshot()
    private val lastPhase: EntityStorage = MutableEntityStorage.create().toSnapshot()

    @Test
    fun `only the last phase is published, the other events pass through`() {
        val events = runBlocking {
            flow {
                emit(ImportEvent.UpdateWorkspaceModel(firstPhase))
                emit(ImportEvent.StdOutput("building"))
                emit(ImportEvent.UpdateWorkspaceModel(lastPhase))
            }.bufferPhases(keepModelOnFailure = false).toList()
        }

        assertEquals(listOf(ImportEvent.StdOutput("building"), ImportEvent.UpdateWorkspaceModel(lastPhase)), events)
    }

    @Test
    fun `a thrown failure drops the buffered phase of a target that has a model`() {
        val events = mutableListOf<ImportEvent>()
        assertThrows(WorkspaceImportException::class.java) {
            runBlocking {
                failingImport().bufferPhases(keepModelOnFailure = false).collect { events.add(it) }
            }
        }

        assertEquals(emptyList<ImportEvent>(), events, "the model imported before the failure must stay in place")
    }

    @Test
    fun `a thrown failure publishes the buffered phase of a target that has no model`() {
        val events = mutableListOf<ImportEvent>()
        assertThrows(WorkspaceImportException::class.java) {
            runBlocking {
                failingImport().bufferPhases(keepModelOnFailure = true).collect { events.add(it) }
            }
        }

        assertEquals(1, events.size)
        assertSame(firstPhase, (events.single() as ImportEvent.UpdateWorkspaceModel).storage)
    }

    @Test
    fun `a reported failure follows the same rule as a thrown one`() {
        fun reportedFailure() = flow {
            emit(ImportEvent.UpdateWorkspaceModel(firstPhase))
            emit(ImportEvent.Failed(WorkspaceImportException("failed", null)))
        }

        val dropped = runBlocking { reportedFailure().bufferPhases(keepModelOnFailure = false).toList() }
        val kept = runBlocking { reportedFailure().bufferPhases(keepModelOnFailure = true).toList() }

        assertEquals(1, dropped.size, "only the failure is reported")
        assertEquals(2, kept.size, "the failure is reported and the phase it managed to build is published")
        assertSame(firstPhase, (kept.last() as ImportEvent.UpdateWorkspaceModel).storage)
    }

    private fun failingImport() = flow<ImportEvent> {
        emit(ImportEvent.UpdateWorkspaceModel(firstPhase))
        throw WorkspaceImportException("failed", null)
    }
}
