// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.createIdeVirtualFileUrlManager
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.toJson
import com.jetbrains.ls.imports.json.workspaceData
import com.jetbrains.ls.test.api.utils.compareWithTestdata
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path

/**
 * Compares an imported [WorkspaceData] against a golden `workspace.json`.
 *
 * The default comparator compares the produced model as is. Each `with*` step returns a new comparator that
 * normalizes the model before the comparison. Normalization removes content that is not stable across an operating
 * system or a network, so a test can assert only the part it cares about.
 */
open class WorkspaceComparator private constructor(
    private val steps: List<(WorkspaceData) -> WorkspaceData>,
) {
    constructor() : this(emptyList())

    /** Returns a new comparator that applies [step] after the existing steps. */
    fun with(step: (WorkspaceData) -> WorkspaceData): WorkspaceComparator = WorkspaceComparator(steps + step)

    fun normalize(data: WorkspaceData): WorkspaceData = steps.fold(data) { acc, step -> step(acc) }

    /**
     * Normalizes [produced], compares it with the golden file, and verifies that the model survives an export and a
     * re-import without a change.
     */
    open fun compare(expectedJsonFile: Path, produced: WorkspaceData, cropJarPaths: (String) -> String) {
        val data = normalize(produced)
        compareWithTestdata(expectedJsonFile, cropJarPaths(toJson(data)))

        val projectDir = expectedJsonFile.parent
        val storageFromData = MutableEntityStorage.create().apply {
            importWorkspaceData(data, projectDir, object : EntitySource {}, createIdeVirtualFileUrlManager(true), false, "JSON")
        }
        assertEquals(data, workspaceData(storageFromData, projectDir))
    }

    companion object {
        /**
         * Skips the golden comparison. Use it when a test asserts the model through an `entityStorageVerifier`, so the
         * test does not depend on a golden that varies by operating system or by network.
         */
        val NONE: WorkspaceComparator = object : WorkspaceComparator() {
            override fun compare(expectedJsonFile: Path, produced: WorkspaceData, cropJarPaths: (String) -> String) = Unit
        }
    }
}
