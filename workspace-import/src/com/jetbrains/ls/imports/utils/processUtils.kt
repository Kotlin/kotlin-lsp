// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.awaitExit
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun ProcessBuilder.runWithErrorReporting(toolName: String, errorReporting: (String) -> Unit) {
    val exitValue = withContext(Dispatchers.IO) {
        val process = try {
            start()
        } catch (e: Exception) {
            throw WorkspaceImportException(
                "Failed to start $toolName process",
                "Cannot execute ${command()} in ${directory()}: ${e.message}",
                e
            )
        }
        val outputJob = logOutput(process, logger<MavenWorkspaceImporter>(), errorReporting)
        process.awaitExit()
        outputJob.join()
        process.exitValue()
    }
    if (exitValue != 0) {
        throw WorkspaceImportException(
            "Failed to import $toolName project",
            "Failed to import $toolName project in ${directory()}:\n${command()} returned $exitValue"
        )
    }
}

internal suspend fun CoroutineScope.logOutput(process: Process, logger: Logger, errorReporting: (String) -> Unit): Job {

    return launch {
        launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { logger.info("STDOUT: $it") }
            }
        }
        launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().forEachLine { it ->
                logger.warn("STDERR: $it")
                errorReporting(it)
            }
        }
    }

}