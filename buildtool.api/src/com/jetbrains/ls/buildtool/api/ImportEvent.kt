// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api

import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.workspace.storage.EntityStorage
import java.nio.file.Path

/**
 * One entry of the files that must re-trigger an import of the target that published it. The platform watches
 * them and asks for the re-import; the tool only declares them, with every model it publishes.
 *
 * A declaration names the inputs of the published model, so it holds the files the import read and the fixed
 * locations it would read if they appeared (a settings file that does not exist yet). The glob of a pattern is
 * matched against the path relative to its directory, so `**` crosses directories.
 */
sealed interface BuildScriptToWatch {
    /** Every file under [directory] whose relative path matches [glob]. */
    data class PatternToWatch(val directory: Path, val glob: String) : BuildScriptToWatch

    /** One file, watched whether or not it exists yet. */
    data class FileToWatch(val path: Path) : BuildScriptToWatch

    /**
     * Every file under [directory] whose relative path matches [glob] is *not* an input, whatever the other
     * entries say: Go's `vendor`, which holds copies of dependency modules rather than modules of the workspace.
     */
    data class FilesToExclude(val directory: Path, val glob: String) : BuildScriptToWatch
}

sealed interface ImportEvent {
    data class UnresolvedDependency(val depName: String) : ImportEvent
    data class StdOutput(val line: @NlsSafe String) : ImportEvent
    data class ErrorOutput(val line: @NlsSafe String) : ImportEvent
    data class ProgressStatus(val text: String) : ImportEvent

    /**
     * New workspace state; replaces whatever the previous [UpdateWorkspaceModel] delivered. [buildScripts] are
     * the files that must re-trigger an import of this target, in full: an import publishes its model in phases,
     * and only the last phase of a held-back import reaches the platform (see `bufferPhases`).
     */
    data class UpdateWorkspaceModel(val storage: EntityStorage, val buildScripts: List<BuildScriptToWatch>) : ImportEvent

    /** The import failed, without taking back the models it already delivered. */
    data class Failed(val cause: Throwable) : ImportEvent
}
