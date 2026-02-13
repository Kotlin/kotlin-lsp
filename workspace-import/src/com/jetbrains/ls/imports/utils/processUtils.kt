// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.awaitExit
import com.jetbrains.ls.imports.api.WorkspaceImportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal suspend fun ProcessBuilder.runAndGetOK(toolName: String, processOutputLogger: Logger? = null) {
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
        process.maybeLogOutput(processOutputLogger)
        process.awaitExit()
        process.exitValue()
    }
    if (exitValue != 0) {
        throw WorkspaceImportException(
            "Failed to import $toolName project",
            "Failed to import $toolName project in ${directory()}:\n${command()} returned $exitValue"
        )
    }
}

internal suspend fun Process.maybeLogOutput(logger: Logger?) {
    if (logger != null) {
        coroutineScope {
            launch {
                runInterruptible(Dispatchers.IO) {
                    inputStream.bufferedReader().lines().forEach { line -> logger.info("STDOUT: $line") }
                }
            }
            launch {
                runInterruptible(Dispatchers.IO) {
                    errorStream.bufferedReader().lines().forEach { line -> logger.info("STDERR: $line") }
                }
            }
        }
    }
}